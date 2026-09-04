import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { enforceNpmAudit } from './enforcer.mjs';
import { sha256 } from '../../../ci/lib/github-advisory-report.mjs';

const baselinePath = 'scripts/ci/github-advisory-baseline.v1.json';
const reportPath = 'tmp/github-advisory-report.json';
const gate = {
  id: 'npm-audit',
  baseline: { path: baselinePath },
  changesetsDir: 'gates/npm-audit/.changesets',
  config: { reportPath, trackedSeverities: ['high', 'critical'] },
};
const rootLockfile = '{"lockfileVersion":3,"packages":{"node_modules/example":{"version":"1.0.0"}}}\n';
const uiLockfile = '{"lockfileVersion":3,"packages":{"node_modules/ui-example":{"version":"1.0.0"}}}\n';
const advisory = (severity = 'high') => ({
  ghsa_id: 'GHSA-35JH-R3H4-6JHM', severity,
  html_url: 'https://github.com/advisories/GHSA-35jh-r3h4-6jhm',
});
const report = (rootAdvisories = [], overrides = {}) => ({
  schema: 'github-advisory-report.v1',
  source: { provider: 'github-global-security-advisories', api_version: '2026-03-10' },
  targets: [
    {
      target_id: 'root', lockfile: 'package-lock.json', available: true, lockfile_sha256: sha256(rootLockfile),
      package_versions: 1, advisories: rootAdvisories, ...overrides,
    },
    {
      target_id: 'ui-web', lockfile: 'modules/ui-web/package-lock.json', available: true, lockfile_sha256: sha256(uiLockfile),
      package_versions: 1, advisories: [],
    },
  ],
});
const baseline = (rootAdvisories = []) => ({
  schema: 'github-advisory-baseline.v1',
  targets: { root: { advisories: rootAdvisories }, 'ui-web': { advisories: [] } },
});

function put(root, relative, value) {
  const file = path.resolve(root, relative);
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, typeof value === 'string' ? value : `${JSON.stringify(value, null, 2)}\n`);
}

async function runFixture({ currentReport, liveBaseline, priorBaseline = liveBaseline, changeset, rebalance = false }) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'justsearch-advisory-gate-'));
  try {
    put(root, reportPath, currentReport);
    put(root, baselinePath, liveBaseline);
    put(root, `_baseline/${baselinePath}`, priorBaseline);
    put(root, 'package-lock.json', rootLockfile);
    put(root, 'modules/ui-web/package-lock.json', uiLockfile);
    if (changeset) put(root, 'gates/npm-audit/.changesets/fixture.md', changeset);
    const result = await enforceNpmAudit({
      repoRoot: root, gate, fixtureMode: true, fixtureRoot: root, baselineRef: 'fixture', rebalance,
    });
    result.writtenBaseline = JSON.parse(readFileSync(path.resolve(root, baselinePath), 'utf8'));
    return result;
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

const declaration = '---\nclassification: declared-regression\ntempdoc: 921\n---\nReviewed fixture advisory.\n';

const within = await runFixture({ currentReport: report([advisory()]), liveBaseline: baseline([advisory()]) });
assert.equal(within.verdict, 'pass');

const silent = await runFixture({ currentReport: report([advisory()]), liveBaseline: baseline([]) });
assert.equal(silent.verdict, 'fail');
assert.ok(silent.findings.some((finding) => finding.ruleId === 'npm-audit/silent-regression'));

const declaredUnpinned = await runFixture({
  currentReport: report([advisory()]), liveBaseline: baseline([]), changeset: declaration,
});
assert.equal(declaredUnpinned.verdict, 'fail');
assert.ok(declaredUnpinned.findings.some((finding) => finding.ruleId === 'npm-audit/declared-regression-without-repin'));

const declaredPinned = await runFixture({
  currentReport: report([advisory()]), liveBaseline: baseline([advisory()]),
  priorBaseline: baseline([]), changeset: declaration,
});
assert.equal(declaredPinned.verdict, 'pass');
assert.ok(declaredPinned.findings.some((finding) => finding.ruleId === 'npm-audit/declared-baseline-shift'));

const silentPin = await runFixture({
  currentReport: report([advisory()]), liveBaseline: baseline([advisory()]), priorBaseline: baseline([]),
});
assert.equal(silentPin.verdict, 'fail');
assert.ok(silentPin.findings.some((finding) => finding.ruleId === 'npm-audit/silent-baseline-shift'));

const escalated = await runFixture({
  currentReport: report([advisory('critical')]), liveBaseline: baseline([advisory('high')]),
});
assert.equal(escalated.verdict, 'fail');
assert.match(escalated.findings[0].message, /severity increased high → critical/);

const unavailable = await runFixture({
  currentReport: report([], { available: false, error: 'deadline exceeded' }), liveBaseline: baseline([]),
});
assert.equal(unavailable.verdict, 'fail');
assert.ok(unavailable.findings.some((finding) => finding.ruleId === 'npm-audit/report-unavailable'));

const stale = await runFixture({
  currentReport: report([], { lockfile_sha256: 'c'.repeat(64) }), liveBaseline: baseline([]),
});
assert.equal(stale.verdict, 'fail');
assert.ok(stale.findings.some((finding) => /digest does not match/.test(finding.message)));

const rebalanceCannotAccept = await runFixture({
  currentReport: report([advisory()]), liveBaseline: baseline([]), rebalance: true,
});
assert.equal(rebalanceCannotAccept.verdict, 'fail');
assert.deepEqual(rebalanceCannotAccept.writtenBaseline.targets.root.advisories, []);

const rebalanceRemovesResolved = await runFixture({
  currentReport: report([]), liveBaseline: baseline([advisory()]), rebalance: true,
});
assert.equal(rebalanceRemovesResolved.verdict, 'pass');
assert.deepEqual(rebalanceRemovesResolved.writtenBaseline.targets.root.advisories, []);

console.log('npm-audit identity enforcer tests: PASS');
