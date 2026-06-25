#!/usr/bin/env node
/**
 * in-flight-liveness gate — tempdoc 575 §15 (Pillar 3b's projection teeth).
 *
 * The liveness derivation ("is an in-flight record's owner still live?") is ONE authority
 * (`isInFlightLive`, `inFlightLiveness.ts`). Every projection that renders an indexing-job in-flight
 * RUNNING state must derive from it — so a phantom "running without a live owner" cannot be silently
 * re-implemented per-surface. This locks the register in `governance/inflight-liveness-projections.v1.json`:
 *
 *  (1) **Positive coverage.** Every registered in-flight render site imports the authority symbol.
 *  (2) **Negative early-warning.** A non-registered site that maps an indexing-job `'PROCESSING'` state
 *      to a `'running'` presentation in close proximity WITHOUT importing the authority is flagged.
 *
 * Coverage = the whole ui-web shell tree (a new file is scanned automatically); no enumerated allowlist
 * beyond the register's render sites.
 *
 * HONEST SCOPE (tempdoc 565 §12.10 — register + DISCIPLINE, not a hard gate): this catches a raw
 * `'PROCESSING' → 'running'` assertion. A re-model that computes the state name, maps a DIFFERENT literal,
 * or hand-rolls a spinner from a count is import-invisible and slips — the same ceiling as
 * check-run-renderers / check-controls-a11y. Early-warning that forces review on a declared second
 * in-flight renderer, not absolute prevention. Scoped to the indexing-job liveness (the 575
 * action-lifecycle stateful concept); the cross-domain generalization is 559/565.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';

const REGISTER = 'governance/inflight-liveness-projections.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');
const SRC = reg.scan.feRoot;
const EXCLUDE = reg.scan.excludeSuffixes;

// Domain-keyed register (v2, 575 §17 Face C). Build: renderSite → required authority symbol;
// the set of authority modules (never self-flagged); and which domains run the phantom early-warning.
const domains = Object.values(reg.domains ?? {});
const requiredSymbolBySite = new Map();
const authModules = new Set();
const phantomDomains = [];
for (const d of domains) {
  authModules.add(norm(d.authority.module));
  for (const site of d.renderSites) requiredSymbolBySite.set(norm(site), d.authority.symbol);
  if (d.phantomCheck) phantomDomains.push(d.authority.symbol);
}

const isExcluded = (p) => EXCLUDE.some((s) => p.endsWith(s));

const files = [];
(function walk(d) {
  for (const e of readdirSync(d)) {
    const p = norm(join(d, e));
    const s = statSync(p);
    if (s.isDirectory()) {
      if (e === 'node_modules' || e === 'generated') continue;
      walk(p);
    } else if (extname(p) === '.ts' && !isExcluded(p)) {
      files.push(p);
    }
  }
})(SRC);

// Scan code, not prose — a doc-comment naming the states is not a use.
const stripComments = (s) =>
  s
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\*.*$/gm, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

// The phantom assertion: the indexing-job state literal 'PROCESSING' near a 'running' presentation
// literal (either order, within ~80 chars) — the raw state→running mapping the authority replaces.
const Q = `['"\\\`]`;
const PHANTOM = new RegExp(
  `(?:${Q}PROCESSING${Q}[\\s\\S]{0,80}?${Q}running${Q})|(?:${Q}running${Q}[\\s\\S]{0,80}?${Q}PROCESSING${Q})`,
);

const failures = [];
let covered = 0;

for (const f of files) {
  const src = stripComments(readFileSync(f, 'utf8'));

  const requiredSymbol = requiredSymbolBySite.get(f);
  if (requiredSymbol) {
    // (1) positive coverage — a registered render site MUST import ITS domain's authority symbol.
    if (!new RegExp(`\\b${requiredSymbol}\\b`).test(src)) {
      failures.push(
        `${f}: registered in-flight render site does not import ${requiredSymbol} — derive RUNNING/` +
          `stalled from the ONE liveness authority for its domain (575 §15/§17 Face C), not raw state.`,
      );
    } else {
      covered++;
    }
    continue;
  }
  if (authModules.has(f)) continue;

  // (2) negative early-warning (phantom-check domains, i.e. indexing-job): an unregistered site asserts
  // PROCESSING→running without importing ANY phantom-domain authority symbol.
  if (PHANTOM.test(src) && !phantomDomains.some((sym) => new RegExp(`\\b${sym}\\b`).test(src))) {
    failures.push(
      `${f}: maps an indexing-job 'PROCESSING' state to a 'running' presentation WITHOUT importing the ` +
        `liveness authority (575 §15) — a phantom-running risk. Derive from the authority, or register ` +
        `this file as a render site in ${REGISTER} only with review.`,
    );
  }
}

if (failures.length) {
  console.error(`in-flight-liveness gate (575 §15/§17): ${failures.length} finding(s):`);
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
console.log(
  `in-flight-liveness gate (575 §15/§17): OK — ${covered} registered render site(s) across ` +
    `${domains.length} liveness domain(s) derive from their authority; no unregistered ` +
    `'PROCESSING'→'running' phantom across ${files.length} shell files.`,
);
