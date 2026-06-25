#!/usr/bin/env node
/**
 * observed-state-collapse gate — tempdoc 557 §2.B / phase-2 B1.
 *
 * Protects the tier-1 collapse that made the FE a CONSUMER of the backend's
 * authoritative status, not a re-deriver. Two invariants:
 *
 *  (1) `statusPoll.ts` exports `StatusSnapshot` as an ALIAS of the generated
 *      `StatusResponse` (the wire authority carrying readiness/composites/stale)
 *      — not a hand-written interface that could silently strip those fields
 *      again (the original §2.B defect).
 *  (2) No production `shell-v0/**` file re-introduces a LOCAL status authority
 *      by declaring its own `interface StatusResponse` / `interface
 *      StatusSnapshot` (HealthSurface used to carry exactly such a lossy local
 *      copy; B1 deleted it).
 *
 * Without this, a future edit can re-add a lossy hand-written status type and
 * the "no data ≠ 0 files / Not Installed / All Operational" defect class
 * silently returns.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';

const SRC = 'modules/ui-web/src';
const STATUS_POLL = 'modules/ui-web/src/shell-v0/utils/statusPoll.ts';
const failures = [];

// (1) statusPoll.ts must alias the generated StatusResponse.
const poll = readFileSync(STATUS_POLL, 'utf8');
if (!/export\s+type\s+StatusSnapshot\s*=\s*StatusResponse\s*;/.test(poll)) {
  failures.push(
    `${STATUS_POLL}: StatusSnapshot must be \`export type StatusSnapshot = StatusResponse;\` ` +
      `(the generated wire authority) — not a hand-written interface (§2.B / B1).`,
  );
}
if (!/from\s+['"][^'"]*api\/generated[^'"]*['"]/.test(poll)) {
  failures.push(
    `${STATUS_POLL}: must import StatusResponse from api/generated (the wire authority) (§2.B / B1).`,
  );
}

// (2) no shell-v0 file may declare a local status interface.
const files = [];
(function walk(d) {
  for (const e of readdirSync(d)) {
    const p = join(d, e);
    const s = statSync(p);
    if (s.isDirectory()) {
      if (e === 'node_modules' || e === 'generated') continue;
      walk(p);
    } else if (['.ts', '.tsx'].includes(extname(p))) files.push(p);
  }
})(join(SRC, 'shell-v0'));

// Catch BOTH forms of a re-introduced local status authority: an `interface`
// declaration, and a `type X = { … }` object-literal redefinition. An alias to
// an identifier (`type StatusSnapshot = StatusResponse`) is the SANCTIONED form
// and is allowed (it projects the generated type) — only the object-literal
// `type … = {` is lossy.
const LOCAL_IFACE = /\binterface\s+(StatusResponse|StatusSnapshot)\b/;
const LOCAL_TYPE_LITERAL = /\btype\s+(StatusResponse|StatusSnapshot)\s*=\s*\{/;
const isTest = (f) => /\.(test|spec)\.tsx?$/.test(f) || f.includes('__fixtures__');
for (const f of files) {
  if (isTest(f)) continue;
  const src = readFileSync(f, 'utf8');
  const m = src.match(LOCAL_IFACE) ?? src.match(LOCAL_TYPE_LITERAL);
  if (m) {
    failures.push(
      `${f}: declares a local \`${m[0].trim()}…\` — a re-introduced lossy status authority. ` +
        `Consume the generated StatusResponse (via aiStateStore / statusPoll), or alias it ` +
        `(\`type StatusSnapshot = StatusResponse\`); don't redefine its shape (§2.B / B1).`,
    );
  }
}

if (failures.length) {
  console.error('observed-state-collapse FAIL:');
  for (const f of failures) console.error('  ' + f);
  process.exit(1);
}
console.log(
  'observed-state-collapse OK — StatusSnapshot aliases the generated StatusResponse; no local status authority.',
);
