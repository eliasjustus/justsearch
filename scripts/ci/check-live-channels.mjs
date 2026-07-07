#!/usr/bin/env node
/**
 * live-channels gate — tempdoc 662 (managed connection budget).
 *
 * The browser caps HTTP/1.1 connections to ~6 per host (Chromium issue 275955, WontFix);
 * WebView2 inherits it. Before 662, the shell's 5 historically always-on SSE streams
 * routinely saturated that pool and starved the cheap status polls under load (tempdoc 649).
 * 662 collapsed those 5 onto ONE multiplexed connection. This gate is the budget's anti-drift
 * teeth — without it, a future feature adding "just one more always-on stream" silently
 * re-creates the exhaustion class. Mirrors `check-declared-surfaces.mjs`'s shape (a positive-
 * coverage register + scan), generalized to a transport-agnostic connection-opener catalog
 * (`governance/live-channels.v1.json`):
 *
 *   (a) every opener PATTERN found by the scan (`new EnvelopeStream(`, `subscribePooled(`,
 *       `new MultiplexedStream(`, the fetch-stream helpers) corresponds to a DECLARED
 *       `channels[]` row for that file — an undeclared opener (a new feature that opens a
 *       long-lived connection without registering it) FAILS.
 *   (b) every declared row's pattern is still actually present in its file — a register row
 *       for code that was removed/refactored away FAILS as stale (keeps the register honest).
 *   (c) the declared `class: "always-on"` channel count does not exceed
 *       `budget.maxAlwaysOnPhysical` — a regression that flips a channel back to always-on,
 *       or adds a new one, without raising the budget FAILS.
 *
 * HONEST SCOPE (mirrors declared-surfaces / execution-surfaces §5): import/grep-visible
 * coverage only. A re-modeled opener (a renamed helper, a hand-rolled fetch-stream loop not
 * matching the declared patterns) is import-invisible and slips past this gate — the accepted
 * norm for every presentation/connection gate in this codebase. The teeth are positive: open a
 * NEW long-lived connection via one of the declared patterns and the gate bites immediately.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const REGISTER = 'governance/live-channels.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');

// Scan code, not prose — a doc-comment naming a pattern is not a use.
const stripComments = (s) =>
  s
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\*.*$/gm, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

const excludeFiles = new Set((reg.scan.excludeFiles || []).map(norm));
const excludeSuffixes = reg.scan.excludeSuffixes || [];

// Collect every non-test .ts/.tsx file under a root, minus the declared substrate exclusions.
const collectTs = (root) => {
  const out = [];
  const walk = (dir) => {
    for (const ent of readdirSync(dir)) {
      const p = join(dir, ent);
      const st = statSync(p);
      if (st.isDirectory()) {
        if (ent === 'node_modules' || ent === 'generated') continue;
        walk(p);
      } else if (/\.tsx?$/.test(ent)) {
        const np = norm(p);
        if (excludeSuffixes.some((suf) => np.endsWith(suf))) continue;
        if (excludeFiles.has(np)) continue;
        out.push(np);
      }
    }
  };
  walk(root);
  return out;
};

const failures = [];
const files = collectTs(reg.scan.root);
const fileSrc = new Map(files.map((f) => [f, stripComments(readFileSync(f, 'utf8'))]));

const openerById = new Map(reg.scan.openers.map((o) => [o.id, o]));
for (const o of reg.scan.openers) {
  if (!o.pattern) {
    failures.push(`scan.openers entry "${o.id}" has no pattern.`);
  }
}

// `found`: Set of "file::openerId" for every pattern match across the scanned tree.
const found = new Set();
for (const [file, src] of fileSrc) {
  for (const opener of reg.scan.openers) {
    const re = new RegExp(opener.pattern, 'g');
    if (re.test(src)) {
      found.add(`${file}::${opener.id}`);
    }
  }
}

// `declared`: Set of "file::openerId" from the register's channels[].
const declared = new Set();
for (const ch of reg.channels) {
  const f = norm(ch.file);
  if (!openerById.has(ch.openerId)) {
    failures.push(
      `channels[] entry for "${f}" declares unknown openerId "${ch.openerId}" — must be one of [${[...openerById.keys()].join(', ')}].`,
    );
    continue;
  }
  declared.add(`${f}::${ch.openerId}`);
}

// (a) Undeclared opener: a pattern match with no corresponding register row.
for (const key of found) {
  if (!declared.has(key)) {
    const [file, openerId] = key.split('::');
    failures.push(
      `${file}: opens a long-lived connection via "${openerById.get(openerId).pattern}" ` +
        `(${openerById.get(openerId).transport}) but is NOT declared in ${REGISTER} — add a ` +
        `channels[] row with a class (always-on/lazy/transient/fallback/dormant), tempdoc 662.`,
    );
  }
}

// (b) Stale declaration: a declared row whose file no longer contains the opener (or the file
// itself no longer exists / was excluded), so the register has drifted from the code.
for (const ch of reg.channels) {
  const f = norm(ch.file);
  const src = fileSrc.get(f);
  if (src === undefined) {
    failures.push(
      `channels[] entry for "${f}" — file not found under ${reg.scan.root} (removed/moved/excluded?) — ` +
        `remove the stale register row or fix the path.`,
    );
    continue;
  }
  const opener = openerById.get(ch.openerId);
  if (!opener) continue; // already reported above
  const re = new RegExp(opener.pattern, 'g');
  if (!re.test(src)) {
    failures.push(
      `${f}: declared as opening "${opener.pattern}" (${ch.openerId}) but that pattern is no ` +
        `longer present — the register row is stale; remove it or the opener was refactored away.`,
    );
  }
}

// (c) Budget: the declared always-on count must not exceed budget.maxAlwaysOnPhysical.
const alwaysOn = reg.channels.filter((c) => c.class === 'always-on');
if (alwaysOn.length > reg.budget.maxAlwaysOnPhysical) {
  failures.push(
    `budget exceeded: ${alwaysOn.length} declared "always-on" channel(s) ` +
      `(${alwaysOn.map((c) => norm(c.file)).join(', ')}) > maxAlwaysOnPhysical ` +
      `(${reg.budget.maxAlwaysOnPhysical}) — the 6-per-host pool minus the polls/headroom this ` +
      `budget reserves (tempdoc 662). Either reduce the always-on count (fold into the poll, make ` +
      `it lazy, or route it onto the existing multiplexed connection) or raise the budget with a ` +
      `documented rationale change.`,
  );
}

const validClasses = new Set(Object.keys(reg.classes || {}));
for (const ch of reg.channels) {
  if (!validClasses.has(ch.class)) {
    failures.push(
      `channels[] entry for "${norm(ch.file)}" has class "${ch.class}", not one of [${[...validClasses].join(', ')}].`,
    );
  }
}

// demandClass (Design §D2's literal ask — breadth/depth/event) is optional per channel and
// documentation-only (no placement is derived from it), but if present it must be a real value.
const validDemandClasses = new Set(Object.keys(reg.demandClasses || {}));
for (const ch of reg.channels) {
  if (ch.demandClass !== undefined && !validDemandClasses.has(ch.demandClass)) {
    failures.push(
      `channels[] entry for "${norm(ch.file)}" has demandClass "${ch.demandClass}", not one of [${[...validDemandClasses].join(', ')}].`,
    );
  }
}

if (failures.length > 0) {
  console.error('✗ live-channels gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
  process.exit(1);
}
console.log(
  `✓ live-channels gate OK — ${reg.channels.length} declared connection opener(s) ` +
    `(${alwaysOn.length} always-on, budget ${reg.budget.maxAlwaysOnPhysical}); every scanned opener ` +
    `pattern is declared, every declared row is still present, budget held (tempdoc 662).`,
);
