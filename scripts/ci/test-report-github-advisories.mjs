import assert from 'node:assert/strict';

import {
  buildAffectsBatches,
  packageSpecsFromLockfileText,
  queryGitHubAdvisories,
  REQUIRED_ADVISORY_TARGETS,
  unavailableAdvisoryTargetReason,
} from './lib/github-advisory-report.mjs';
import { collectAdvisoryTarget } from './report-github-advisories.mjs';

const lockfile = JSON.stringify({
  lockfileVersion: 3,
  packages: {
    '': { name: 'fixture', version: '1.0.0' },
    'node_modules/lodash': { version: '4.17.20' },
    'node_modules/outer/node_modules/@scope/inner': { version: '2.0.0' },
  },
});
assert.deepEqual(packageSpecsFromLockfileText(lockfile), ['@scope/inner@2.0.0', 'lodash@4.17.20']);
assert.throws(() => packageSpecsFromLockfileText('{}'), /package-lock schema/);
assert.deepEqual(
  REQUIRED_ADVISORY_TARGETS.map(({ targetId, lockfile: path }) => [targetId, path]),
  [
    ['root', 'package-lock.json'],
    ['ui-web', 'modules/ui-web/package-lock.json'],
    ['shell', 'modules/shell/package-lock.json'],
    ['runtime-client', 'packages/runtime-client/package-lock.json'],
    ['wire-contract', 'scripts/wire-contract/package-lock.json'],
  ],
  'every production npm lockfile must have one explicit advisory target',
);

const batches = buildAffectsBatches(['a@1.0.0', 'b@1.0.0', 'c@1.0.0'], { maxSpecs: 2 });
assert.deepEqual(batches, [['a@1.0.0', 'b@1.0.0'], ['c@1.0.0']]);

const advisory = (id, severity = 'high') => ({
  ghsa_id: id,
  severity,
  html_url: `https://github.com/advisories/${id}`,
});
let calls = 0;
const fetched = await queryGitHubAdvisories(['lodash@4.17.20'], {
  fetchImpl: async (url, options) => {
    calls += 1;
    assert.equal(options.method, 'GET');
    assert.match(String(url), /ecosystem=npm/);
    assert.equal(options.headers.Authorization, 'Bearer test-token');
    return {
      ok: true,
      status: 200,
      headers: { get: () => null },
      json: async () => calls === 1
        ? Array.from({ length: 100 }, () => advisory('GHSA-35JH-R3H4-6JHM'))
        : [advisory('GHSA-35JH-R3H4-6JHM'), advisory('GHSA-29MW-WPGM-HMR9', 'moderate')],
    };
  },
  token: 'test-token',
  retries: 0,
  timeoutSignalFactory: () => undefined,
});
assert.equal(calls, 2, 'a full page must trigger pagination');
assert.deepEqual(fetched.map((row) => row.ghsa_id), ['GHSA-29MW-WPGM-HMR9', 'GHSA-35JH-R3H4-6JHM']);

let retryCalls = 0;
const retried = await queryGitHubAdvisories(['lodash@4.17.20'], {
  fetchImpl: async () => {
    retryCalls += 1;
    if (retryCalls === 1) throw new Error('socket reset');
    return { ok: true, status: 200, headers: { get: () => null }, json: async () => [] };
  },
  retries: 1,
  sleep: async () => {},
  timeoutSignalFactory: () => undefined,
});
assert.deepEqual(retried, []);
assert.equal(retryCalls, 2);

let forbiddenCalls = 0;
await assert.rejects(
  queryGitHubAdvisories(['lodash@4.17.20'], {
    fetchImpl: async () => {
      forbiddenCalls += 1;
      return { ok: false, status: 403, headers: { get: () => null } };
    },
    retries: 2,
    sleep: async () => {},
    timeoutSignalFactory: () => undefined,
  }),
  /HTTP 403/,
);
assert.equal(forbiddenCalls, 1, 'non-transient HTTP failures must not be retried');

const timeoutSignals = [];
await assert.rejects(
  queryGitHubAdvisories(['lodash@4.17.20'], {
    fetchImpl: async (_url, options) => {
      timeoutSignals.push(options.signal);
      throw new DOMException('deadline exceeded', 'TimeoutError');
    },
    timeoutMs: 1234,
    retries: 1,
    sleep: async () => {},
    timeoutSignalFactory: (ms) => ({ deadline_ms: ms }),
  }),
  /deadline exceeded/,
);
assert.deepEqual(timeoutSignals, [{ deadline_ms: 1234 }, { deadline_ms: 1234 }]);

await assert.rejects(
  queryGitHubAdvisories(['lodash@4.17.20'], {
    fetchImpl: async () => ({ ok: true, status: 200, headers: { get: () => null }, json: async () => ({}) }),
    retries: 0,
    timeoutSignalFactory: () => undefined,
  }),
  /JSON array/,
);

const goodTarget = {
  available: true,
  lockfile_sha256: 'a'.repeat(64),
  package_versions: 2,
  advisories: [advisory('GHSA-35JH-R3H4-6JHM')],
};
assert.equal(unavailableAdvisoryTargetReason(goodTarget), null);
assert.match(unavailableAdvisoryTargetReason({ ...goodTarget, available: false, error: 'timeout' }), /timeout/);
assert.match(unavailableAdvisoryTargetReason({ ...goodTarget, advisories: [advisory('bad')] }), /invalid ghsa_id/);

const unavailable = await collectAdvisoryTarget({
  repoRoot: process.cwd(),
  target: { targetId: 'missing', lockfile: 'not-present.lock' },
  query: async () => { throw new Error('must not query'); },
});
assert.equal(unavailable.available, false);
assert.match(unavailable.error, /ENOENT/);

console.log('test-report-github-advisories: PASS');
