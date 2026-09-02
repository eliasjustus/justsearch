/**
 * Integration tests for the adr-coverage enforcer — drives enforceAdrCoverage over
 * scaffolded temp trees and asserts on the FINDINGS, not just the verdict.
 *
 * The kernel self-test only compares a fixture's verdict (run.mjs), so two rules that
 * both fail are indistinguishable there. These assertions are what tell "the probe rule
 * fired" apart from "the pre-existing Covers rule fired" (tempdoc 884 review B1).
 *
 * Run with: `node scripts/governance/gates/adr-coverage/enforcer.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { enforceAdrCoverage } from './enforcer.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

const GATE = { changesetsDir: 'gates/adr-coverage/.changesets', config: { adrDir: 'docs/decisions' } };

function scaffold(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'adr-cov-'));
  tmpDirs.push(root);
  fs.mkdirSync(path.join(root, 'gates/adr-coverage/.changesets'), { recursive: true });
  for (const [rel, content] of Object.entries(files)) {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  }
  return root;
}

async function enforce(root) {
  return enforceAdrCoverage({ repoRoot: root, gate: GATE, baselineRef: null, mode: 'gate', fixtureMode: true, fixtureRoot: root });
}
async function run(label, fn) {
  try { await fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}
const ids = (r) => r.findings.map((f) => f.ruleId);
const msgFor = (r, id) => r.findings.filter((f) => f.ruleId === id).map((f) => f.message).join(' | ');

const register = (probes) => JSON.stringify({ version: 1, probes });
const FRESH = new Date().toISOString().slice(0, 10);

// --------------------------------------------------------------- Covers (tempdoc 530 §2.7)

await run('Covers glob that resolves → pass, no stale-coverage', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\ncovers: src/a.ts\nprobes: none - fixture.\nlast_reviewed: ${FRESH}\n---\n`,
    'src/a.ts': 'x',
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', ids(r).join(','));
  assert.ok(!ids(r).includes('adr-coverage/stale-coverage'));
});

await run('Covers glob matching nothing → fail with stale-coverage', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\ncovers: src/gone.ts\nprobes: none - fixture.\nlast_reviewed: ${FRESH}\n---\n`,
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail');
  assert.ok(ids(r).includes('adr-coverage/stale-coverage'), ids(r).join(','));
});

await run('Covers as a YAML list still validates every glob', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\ncovers:\n  - src/a.ts\n  - src/gone.ts\nprobes: none - fixture.\nlast_reviewed: ${FRESH}\n---\n`,
    'src/a.ts': 'x',
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, 'adr-coverage/stale-coverage'), /src\/gone\.ts/);
});

// ----------------------------------------------------------------------- probe-failed

await run('declared probe that holds → pass, no probe finding', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: accepted\nprobes:\n  - p1\nlast_reviewed: ${FRESH}\n---\n`,
    'governance/adr-probes.v1.json': register([
      { id: 'p1', adr: '0001', premise: 'The flag is gone and stays gone.', kind: 'grep-absent', pattern: 'FLAG', paths: ['src'] },
    ]),
    'src/a.ts': 'clean',
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', ids(r).join(','));
  assert.ok(!ids(r).includes('adr-coverage/probe-failed'));
});

await run('premise drifted → probe-failed, quoting the premise and the amendment procedure', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: accepted\nprobes:\n  - p1\nlast_reviewed: ${FRESH}\n---\n`,
    'governance/adr-probes.v1.json': register([
      { id: 'p1', adr: '0001', premise: 'The flag is gone and stays gone.', kind: 'grep-absent', pattern: 'FLAG', paths: ['src'] },
    ]),
    'src/a.ts': 'if (FLAG) {}',
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail');
  const m = msgFor(r, 'adr-coverage/probe-failed');
  assert.match(m, /The flag is gone and stays gone\./, 'the premise must be quoted verbatim');
  assert.match(m, /How to re-examine an ADR/, 'the amendment procedure must be named');
  assert.match(m, /not to edit the probe until it passes/);
});

await run('probes: list is parsed as a LIST (the R3 regression guard)', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: accepted\nprobes:\n  - p1\n  - p2\nlast_reviewed: ${FRESH}\n---\n`,
    'governance/adr-probes.v1.json': register([
      { id: 'p1', adr: '0001', premise: 'First premise about the code.', kind: 'grep-present', pattern: 'a', paths: ['src/a.ts'] },
      { id: 'p2', adr: '0001', premise: 'Second premise about the code.', kind: 'grep-present', pattern: 'zzz', paths: ['src/a.ts'] },
    ]),
    'src/a.ts': 'a',
  });
  const r = await enforce(root);
  // p2 fails: if the list parsed as an empty string, NOTHING would run and this would pass.
  assert.equal(r.verdict, 'fail', 'the second list entry must actually be evaluated');
  assert.match(msgFor(r, 'adr-coverage/probe-failed'), /Second premise/);
});

await run('declared probe id absent from the register → probe-failed', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: accepted\nprobes:\n  - ghost\nlast_reviewed: ${FRESH}\n---\n`,
    'governance/adr-probes.v1.json': register([]),
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, 'adr-coverage/probe-failed'), /not an entry in/);
});

await run('register probe no ADR claims → probe-failed (an orphan never runs)', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: accepted\nprobes: none - fixture.\nlast_reviewed: ${FRESH}\n---\n`,
    'governance/adr-probes.v1.json': register([
      { id: 'p1', adr: '0001', premise: 'A premise nobody claims from frontmatter.', kind: 'grep-present', pattern: 'a', paths: ['src/a.ts'] },
    ]),
    'src/a.ts': 'a',
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, 'adr-coverage/probe-failed'), /does not list 'p1'/);
});

await run('unparseable probe register → fail, not a crashed kernel run', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: accepted\nprobes: none - fixture.\nlast_reviewed: ${FRESH}\n---\n`,
    'governance/adr-probes.v1.json': '{ not json',
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, 'adr-coverage/probe-failed'), /not parseable JSON/);
});

await run('unparseable ADR frontmatter → fail, not a crashed kernel run', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': '---\ntitle: "unterminated\n  probes: [\n---\n',
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, 'adr-coverage/probe-failed'), /not parseable YAML/);
});

// -------------------------------------------------------------------------- no-probe

await run('live ADR with no probe and no reason → no-probe WARNING, verdict still pass', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\nlast_reviewed: ${FRESH}\n---\n`,
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', 'no-probe warns, it does not block');
  assert.ok(ids(r).includes('adr-coverage/no-probe'), ids(r).join(','));
  assert.equal(r.findings.find((f) => f.ruleId === 'adr-coverage/no-probe').level, 'warning');
});

await run('compound status prefix-matches as live (R1)', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: accepted - mechanism superseded by tempdoc 564\nlast_reviewed: ${FRESH}\n---\n`,
  });
  assert.ok(ids(await enforce(root)).includes('adr-coverage/no-probe'));
});

await run('superseded ADR owes no probe', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: superseded\nlast_reviewed: ${FRESH}\n---\n`,
  });
  assert.ok(!ids(await enforce(root)).includes('adr-coverage/no-probe'));
});

await run('a stated reason satisfies no-probe', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\nprobes: none - the premise is a scope judgement.\nlast_reviewed: ${FRESH}\n---\n`,
  });
  assert.ok(!ids(await enforce(root)).includes('adr-coverage/no-probe'));
});

await run('a bare `probes: none` states nothing → still no-probe', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\nprobes: none\nlast_reviewed: ${FRESH}\n---\n`,
  });
  assert.ok(ids(await enforce(root)).includes('adr-coverage/no-probe'));
});

await run('an ADR with NO frontmatter still owes a probe and a review date', async () => {
  const root = scaffold({ 'docs/decisions/0001-x.md': '# ADR with no frontmatter\n' });
  const r = await enforce(root);
  assert.ok(ids(r).includes('adr-coverage/no-probe'), ids(r).join(','));
  assert.ok(ids(r).includes('adr-coverage/review-stale'), ids(r).join(','));
  assert.equal(ids(r).filter((i) => i === 'adr-coverage/no-covers-field').length, 1, 'exactly one Covers note');
});

await run('README.md is the index, not a decision — no probe or cadence rules', async () => {
  const root = scaffold({ 'docs/decisions/README.md': '---\ntitle: Index\nstatus: stable\n---\n' });
  const r = await enforce(root);
  assert.ok(!ids(r).includes('adr-coverage/no-probe'));
  assert.ok(!ids(r).includes('adr-coverage/review-stale'));
});

// ----------------------------------------------------------------------- review-stale

await run('last_reviewed inside the window → no warning', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\nprobes: none - fixture.\nlast_reviewed: ${FRESH}\n---\n`,
  });
  assert.ok(!ids(await enforce(root)).includes('adr-coverage/review-stale'));
});

await run('last_reviewed older than the window → review-stale WARNING, verdict pass', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': '---\ntitle: X\nstatus: stable\nprobes: none - fixture.\nlast_reviewed: 2019-01-01\n---\n',
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass');
  assert.equal(r.findings.find((f) => f.ruleId === 'adr-coverage/review-stale').level, 'warning');
  assert.match(msgFor(r, 'adr-coverage/review-stale'), /2019-01-01 \(\d+ days ago, threshold 183\)/);
});

await run('missing last_reviewed → review-stale', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': '---\ntitle: X\nstatus: stable\nprobes: none - fixture.\n---\n',
  });
  assert.match(msgFor(await enforce(root), 'adr-coverage/review-stale'), /no 'last_reviewed' date/);
});

await run('an unquoted YAML date parses as a Date and is normalized, not stringified as an ISO timestamp', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': '---\ntitle: X\nstatus: stable\nprobes: none - fixture.\nlast_reviewed: 2019-01-01\n---\n',
  });
  assert.match(msgFor(await enforce(root), 'adr-coverage/review-stale'), /last reviewed 2019-01-01 /);
});

await run('the review window is configurable', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\nprobes: none - fixture.\nlast_reviewed: ${FRESH}\n---\n`,
  });
  const r = await enforceAdrCoverage({
    repoRoot: root, gate: { ...GATE, config: { ...GATE.config, reviewStaleDays: -1 } },
    baselineRef: null, mode: 'gate', fixtureMode: true, fixtureRoot: root,
  });
  assert.ok(ids(r).includes('adr-coverage/review-stale'));
});

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`adr-coverage enforcer.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`adr-coverage enforcer.test: all ${passed} checks passed`);
