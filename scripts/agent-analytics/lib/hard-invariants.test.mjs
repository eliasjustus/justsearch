/**
 * Tempdoc 681 §De-risk — regression guard for hard-invariants.mjs.
 *
 * `hardInvariants()` is the single-authority projection of AGENTS.md's
 * `## Hard Invariants` into every subagent brief (`subagent-guide.mjs`), and it
 * is fail-open: a parse-breaking restructure of AGENTS.md would silently strip
 * the invariants from all subagent briefs. No test pinned the parse contract
 * before 681's instruction-layer edits; this file is that pin.
 *
 * Run with: `node scripts/agent-analytics/lib/hard-invariants.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { hardInvariants } from './hard-invariants.mjs';

let failures = 0;
function check(name, fn) {
  try {
    fn();
    console.log(`ok   ${name}`);
  } catch (e) {
    failures++;
    console.error(`FAIL ${name}: ${e.message}`);
  }
}

const inv = hardInvariants();

check('parses a non-empty invariant list from the live AGENTS.md', () => {
  assert.ok(Array.isArray(inv), 'returns an array');
  assert.ok(inv.length >= 6, `expected >= 6 invariants, got ${inv.length} — the section shrank or the parse broke`);
});

check('strips rule anchors from every item', () => {
  for (const text of inv) {
    assert.ok(!/<!--\s*rule:/.test(text), `anchor leaked into projected text: ${text}`);
  }
});

// One distinctive token per invariant, as a canary that the projection is neither empty nor
// garbled. Invariant #2's canary was `127.0.0.1` until tempdoc 884 rewrote that line to name the
// whole trust boundary (loopback bind + Host allowlist + mutation token, ADR-0046); `loopback`
// still occurs exactly once in CLAUDE.md, so the canary is as discriminating as before. The bind
// ADDRESS is not asserted here any more because it is now asserted where it lives: the
// `adr-0046-loopback-bind-literal` probe in governance/adr-probes.v1.json pins
// `app.start("127.0.0.1", ...)` in LocalApiServer.java itself.
check('projects the load-bearing invariants by content', () => {
  const all = inv.join('\n');
  for (const needle of ['Lucene', 'loopback', 'Lit', 'locale-invariant']) {
    assert.ok(all.includes(needle), `expected an invariant mentioning "${needle}"`);
  }
});

check('every item is a numbered-list payload, not a heading or table row', () => {
  for (const text of inv) {
    assert.ok(!text.startsWith('#') && !text.startsWith('|'), `unexpected item shape: ${text}`);
  }
});

if (failures > 0) {
  console.error(`\n${failures} failure(s).`);
  process.exit(1);
}
console.log('\nall hard-invariants checks passed.');
