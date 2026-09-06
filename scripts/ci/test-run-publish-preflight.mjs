#!/usr/bin/env node

import assert from 'node:assert/strict';

import {
  assertCleanCandidate,
  commandForPlatform,
  executeLocalSubsets,
} from './run-publish-preflight.mjs';
import { validatePublicCiLocalRepro } from './lib/public-ci-local-repro.mjs';

const signalPolicy = {
  workflows: [{ blocking: true, requiredStatusChecks: ['Local', 'Hosted'] }],
};
const validManifest = {
  kind: 'justsearch-public-ci-local-repro.v1',
  version: 1,
  contexts: [
    { check: 'Local', mode: 'local-subset', commands: ['one', 'two'] },
    { check: 'Hosted', mode: 'hosted-only', reason: 'external state' },
  ],
};

assert.deepEqual(validatePublicCiLocalRepro({ manifest: validManifest, signalPolicy }), []);
assert.match(validatePublicCiLocalRepro({
  manifest: { ...validManifest, contexts: [validManifest.contexts[0]] },
  signalPolicy,
}).join('\n'), /Hosted: required check must appear exactly once/);
assert.match(validatePublicCiLocalRepro({
  manifest: { ...validManifest, contexts: [...validManifest.contexts, validManifest.contexts[0]] },
  signalPolicy,
}).join('\n'), /Local: required check must appear exactly once/);

const calls = [];
const status = executeLocalSubsets(validManifest, {
  checkCandidate() {},
  cwd: 'fixture',
  run(command, options) {
    calls.push({ command, options });
    return { status: command === 'two' ? 7 : 0 };
  },
});
assert.equal(status, 7);
assert.deepEqual(calls.map((call) => call.command), ['one', 'two']);
assert.ok(calls.every((call) => call.options.cwd === 'fixture' && call.options.shell === true));

assert.equal(commandForPlatform('./gradlew.bat checkLicense', 'win32'), '.\\gradlew.bat checkLicense');
assert.equal(commandForPlatform('./gradlew.bat checkLicense', 'linux'), './gradlew.bat checkLicense');
assert.equal(commandForPlatform('node scripts/ci/check.mjs', 'win32'), 'node scripts/ci/check.mjs');

const gitStatusCalls = [];
assert.throws(
  () => assertCleanCandidate({
    cwd: 'dirty-fixture',
    run(command, args, options) {
      gitStatusCalls.push({ command, args, options });
      return { status: 0, stdout: '?? candidate-secret.txt\n' };
    },
  }),
  /requires a clean candidate/,
);
assert.deepEqual(gitStatusCalls[0].args, ['status', '--porcelain=v1', '--untracked-files=all']);
assert.equal(gitStatusCalls[0].options.cwd, 'dirty-fixture');
assert.doesNotThrow(() => assertCleanCandidate({
  cwd: 'clean-fixture',
  run() { return { status: 0, stdout: '' }; },
}));

console.log('test-run-publish-preflight: PASS');
