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

// ------------------------------------------------------- review window (single authority)

await run('the review window comes from the probe register, not the hardcoded fallback', async () => {
  // The register declares a window DIFFERENT from the 183-day fallback, and an ADR reviewed
  // 200 days ago. Under the fallback (183) it would be stale; under the register (365) it is
  // not. Only a test whose register value differs from 183 can tell the two paths apart.
  const days200 = new Date(Date.now() - 200 * 86_400_000).toISOString().slice(0, 10);
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\nprobes: none - fixture.\nlast_reviewed: ${days200}\n---\n`,
    'governance/adr-probes.v1.json': JSON.stringify({ version: 1, reviewStaleDays: 365, probes: [] }),
  });
  const r = await enforceAdrCoverage({
    repoRoot: root, gate: { changesetsDir: GATE.changesetsDir, config: { adrDir: 'docs/decisions' } },
    baselineRef: null, mode: 'gate', fixtureMode: true, fixtureRoot: root,
  });
  assert.ok(!ids(r).includes('adr-coverage/review-stale'),
    `register's 365-day window must win over the 183-day fallback; got ${ids(r).join(',')}`);
});

await run('a register window SHORTER than the fallback also wins', async () => {
  const days10 = new Date(Date.now() - 10 * 86_400_000).toISOString().slice(0, 10);
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\nprobes: none - fixture.\nlast_reviewed: ${days10}\n---\n`,
    'governance/adr-probes.v1.json': JSON.stringify({ version: 1, reviewStaleDays: 5, probes: [] }),
  });
  const r = await enforceAdrCoverage({
    repoRoot: root, gate: { changesetsDir: GATE.changesetsDir, config: { adrDir: 'docs/decisions' } },
    baselineRef: null, mode: 'gate', fixtureMode: true, fixtureRoot: root,
  });
  assert.match(msgFor(r, 'adr-coverage/review-stale'), /threshold 5\)/, ids(r).join(','));
});

// ------------------------------------------------- risk register instruments (tempdoc 884 D)

const CLEAN_ADR = `---\ntitle: X\nstatus: stable\nprobes: none - fixture.\nlast_reviewed: ${FRESH}\n---\n`;
const RISK_PATH = 'docs/reference/architectural-risks.md';

/** One `## RISK-NNN:` section. `instrument === null` omits the field entirely. */
function riskRow(id, instrument, status = 'Accepted') {
  const field = instrument === null ? '' : `**Instrument:** \`${instrument}\`\n\n`;
  return `## ${id}: Fixture row ${id}\n\n`
    + `**Category:** maintainability | **Status:** ${status}\n\n`
    + `**Reassess when:** never\n\n`
    + field
    + `**Owner tempdoc:** none - fixture.\n\n`
    + `**Last reviewed:** 2026-09-02\n\n`;
}
function riskDoc(...rows) {
  return `---\ntitle: Risks\ntype: reference\n---\n\n# Risks\n\n## How to use this document\n\nPreamble prose that is not a risk section.\n\n${rows.join('')}## Resolved\n\nAn index, not a risk section.\n`;
}
/** Scaffold a tree whose ONLY possible failure is the risk rule under test. */
function riskTree(instrument, extra = {}, { status = 'Accepted', rows = null } = {}) {
  return scaffold({
    'docs/decisions/0001-x.md': CLEAN_ADR,
    [RISK_PATH]: riskDoc(...(rows ?? [riskRow('RISK-001', instrument, status)])),
    ...extra,
  });
}
const RISK_UNRESOLVED = 'adr-coverage/risk-instrument-unresolved';
const RISK_NO_INSTRUMENT = 'adr-coverage/risk-no-instrument';
const RISK_MALFORMED = 'adr-coverage/risk-register-malformed';

await run('an absent risk register is silent — no risk findings, verdict pass', async () => {
  const root = scaffold({ 'docs/decisions/0001-x.md': CLEAN_ADR });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', ids(r).join(','));
  assert.ok(!ids(r).some((i) => i.startsWith('adr-coverage/risk-')), ids(r).join(','));
});

// --- gate:
await run('gate: a registered gate id resolves', async () => {
  const r = await enforce(riskTree('gate:adr-coverage', {
    'governance/registry.v1.json': JSON.stringify({ gates: [{ id: 'adr-coverage' }] }),
  }));
  assert.equal(r.verdict, 'pass', ids(r).join(','));
  assert.ok(!ids(r).includes(RISK_UNRESOLVED));
});

await run('gate: an unknown gate id → risk-instrument-unresolved, verdict fail', async () => {
  const r = await enforce(riskTree('gate:no-such-gate', {
    'governance/registry.v1.json': JSON.stringify({ gates: [{ id: 'adr-coverage' }] }),
  }));
  assert.equal(r.verdict, 'fail', ids(r).join(','));
  assert.match(msgFor(r, RISK_UNRESOLVED), /no gate with id 'no-such-gate'/);
});

// --- check:
await run('check: an existing scripts/ci check resolves', async () => {
  const r = await enforce(riskTree('check:scripts/ci/check-thing.mjs', {
    'scripts/ci/check-thing.mjs': '// check\n',
  }));
  assert.equal(r.verdict, 'pass', ids(r).join(','));
});

await run('check: a missing check → risk-instrument-unresolved, verdict fail', async () => {
  const r = await enforce(riskTree('check:scripts/ci/check-gone.mjs'));
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, RISK_UNRESOLVED), /'scripts\/ci\/check-gone\.mjs' does not exist/);
});

// --- test:
await run('test: file present and member declared resolves', async () => {
  const r = await enforce(riskTree('test:src/T.java#someTestMethod', {
    'src/T.java': 'class T { void someTestMethod() {} }',
  }));
  assert.equal(r.verdict, 'pass', ids(r).join(','));
});

await run('test: member renamed → risk-instrument-unresolved, verdict fail', async () => {
  const r = await enforce(riskTree('test:src/T.java#someTestMethod', {
    'src/T.java': 'class T { void renamedAwayFromTheRegister() {} }',
  }));
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, RISK_UNRESOLVED), /exists but no longer declares 'someTestMethod'/);
});

await run('test: file deleted → risk-instrument-unresolved, verdict fail', async () => {
  const r = await enforce(riskTree('test:src/Gone.java#someTestMethod'));
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, RISK_UNRESOLVED), /'src\/Gone\.java' does not exist/);
});

// --- metric:
await run('metric: an id present under modules/**/src/main resolves', async () => {
  const r = await enforce(riskTree('metric:queue.dequeue_rate_per_min', {
    'modules/worker/src/main/java/M.java': 'static final String Q = "queue.dequeue_rate_per_min";',
  }));
  assert.equal(r.verdict, 'pass', ids(r).join(','));
});

await run('metric: an id that appears only in a TEST source does not resolve', async () => {
  // The premise is "the metric is built", not "someone mentioned it".
  const r = await enforce(riskTree('metric:queue.dequeue_rate_per_min', {
    'modules/worker/src/main/java/M.java': 'class M {}',
    'modules/worker/src/test/java/MTest.java': '"queue.dequeue_rate_per_min"',
  }));
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, RISK_UNRESOLVED), /appears in no file under modules/);
  assert.match(msgFor(r, RISK_UNRESOLVED), /tempdoc: form instead of naming it as if it were/);
});

// --- tempdoc:
await run('tempdoc: an existing tempdoc with a matching heading resolves', async () => {
  const r = await enforce(riskTree('tempdoc:885#Item 21 - job queue', {
    'docs/tempdocs/885-lane-c.md': '# 885\n\n### Item 21 - job queue\n\nbody\n',
  }));
  assert.equal(r.verdict, 'pass', ids(r).join(','));
});

await run('tempdoc: heading renamed away → risk-instrument-unresolved, verdict fail', async () => {
  const r = await enforce(riskTree('tempdoc:885#Item 21 - job queue', {
    'docs/tempdocs/885-lane-c.md': '# 885\n\n### Item 21 - renamed\n\nbody\n',
  }));
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, RISK_UNRESOLVED), /no heading containing 'Item 21 - job queue'/);
});

await run('tempdoc: prose containing the words but no HEADING does not resolve', async () => {
  const r = await enforce(riskTree('tempdoc:885#Item 21 - job queue', {
    'docs/tempdocs/885-lane-c.md': '# 885\n\nThis paragraph mentions Item 21 - job queue in prose.\n',
  }));
  assert.equal(r.verdict, 'fail', 'a prose mention is not an owning section');
});

await run('tempdoc: an unnumbered lane → risk-instrument-unresolved naming the "do not invent" remedy', async () => {
  const r = await enforce(riskTree('tempdoc:999#Item 1 - lane D', {
    'docs/tempdocs/885-lane-c.md': '# 885\n',
  }));
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, RISK_UNRESOLVED), /do not invent a tempdoc number/);
});

// --- none / missing
await run("none - <reason> → risk-no-instrument WARNING quoting the reason, verdict pass", async () => {
  const r = await enforce(riskTree('none - lane D has no tempdoc number yet.'));
  assert.equal(r.verdict, 'pass', 'a stated no-instrument reason warns, it does not block');
  assert.equal(r.findings.find((f) => f.ruleId === RISK_NO_INSTRUMENT).level, 'warning');
  assert.match(msgFor(r, RISK_NO_INSTRUMENT), /lane D has no tempdoc number yet\./);
});

await run('a bare `none` is NOT accepted as a reason', async () => {
  const r = await enforce(riskTree('none'));
  assert.equal(r.verdict, 'pass');
  assert.match(msgFor(r, RISK_NO_INSTRUMENT), /a bare 'none' states nothing/);
  assert.ok(!/reason stated/.test(msgFor(r, RISK_NO_INSTRUMENT)), 'a bare none must not read as a stated reason');
});

await run('a risk section with no **Instrument:** field at all → risk-no-instrument', async () => {
  const r = await enforce(riskTree(null));
  assert.equal(r.verdict, 'pass');
  assert.match(msgFor(r, RISK_NO_INSTRUMENT), /no '\*\*Instrument:\*\*' field/);
});

await run('an unrecognised instrument form → risk-instrument-unresolved, verdict fail', async () => {
  const r = await enforce(riskTree('probe:adr-0004-gpu-mutual-exclusion'));
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, RISK_UNRESOLVED), /unrecognised instrument form/);
});

// --- malformed / structural
await run('a register with no RISK section → risk-register-malformed, verdict fail', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': CLEAN_ADR,
    [RISK_PATH]: '---\ntitle: Risks\n---\n\n# Risks\n\n### RISK-001: wrong heading level\n\n**Instrument:** `gate:x`\n',
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, RISK_MALFORMED), /no `## RISK-NNN: <title>` section was found/);
});

await run('a reused RISK id → risk-register-malformed, verdict fail', async () => {
  const root = riskTree(null, {}, {
    rows: [riskRow('RISK-001', 'none - fixture.'), riskRow('RISK-001', 'none - fixture.')],
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, RISK_MALFORMED), /RISK-001 appear more than once/);
});

await run('a Resolved row is still evaluated — its instrument is what notices a regression', async () => {
  const r = await enforce(riskTree('test:src/Gone.java#argvEnablesNativeAccess', {}, { status: 'Resolved' }));
  assert.equal(r.verdict, 'fail', 'Resolved is not an exemption');
  assert.match(msgFor(r, RISK_UNRESOLVED), /RISK-001/);
});

await run('every row is evaluated, not just the first', async () => {
  const root = riskTree(null, { 'src/T.java': 'class T { void ok() {} }' }, {
    rows: [riskRow('RISK-001', 'test:src/T.java#ok'), riskRow('RISK-002', 'test:src/T.java#vanished')],
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail');
  assert.match(msgFor(r, RISK_UNRESOLVED), /RISK-002/);
  assert.ok(!/RISK-001/.test(msgFor(r, RISK_UNRESOLVED)), 'the resolving row must not be reported');
});

await run('the unresolved message quotes the ref verbatim, names the fix, and states the intent', async () => {
  const r = await enforce(riskTree('test:src/Gone.java#someTestMethod'));
  const m = msgFor(r, RISK_UNRESOLVED);
  assert.match(m, /'test:src\/Gone\.java#someTestMethod'/, 'the reference must be quoted verbatim');
  assert.match(m, /does not exist/, 'it must say why it did not resolve');
  assert.match(m, /build the instrument or amend the risk row/, 'it must name the fix');
  assert.match(m, /never to delete the reference/);
  assert.match(m, /closed without building what it promised/, 'it must state the intent');
});

await run('the risk rules do not disturb the ADR rules in the same run', async () => {
  const root = scaffold({
    'docs/decisions/0001-x.md': `---\ntitle: X\nstatus: stable\ncovers: src/gone.ts\nprobes: none - fixture.\nlast_reviewed: ${FRESH}\n---\n`,
    [RISK_PATH]: riskDoc(riskRow('RISK-001', 'none - fixture.')),
  });
  const r = await enforce(root);
  assert.ok(ids(r).includes('adr-coverage/stale-coverage'), ids(r).join(','));
  assert.ok(ids(r).includes(RISK_NO_INSTRUMENT), ids(r).join(','));
});

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`adr-coverage enforcer.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`adr-coverage enforcer.test: all ${passed} checks passed`);
