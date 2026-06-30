#!/usr/bin/env node

import assert from 'node:assert/strict';

import { validateUnitTestShardPolicy } from './verify-unit-test-shard-policy.mjs';

const tasks = [':modules:alpha:test', ':modules:beta:test'];

function lane(overrides = {}) {
  return {
    lane: 'alpha',
    requiredCheck: 'Unit tests (alpha)',
    artifact: 'unit-test-attribution-alpha',
    runnerLabel: 'windows-latest/alpha',
    owner: 'alpha owner',
    platformClass: 'mixed/needs targeted experiment',
    platformRiskNotes: ['risk note'],
    gradleTasks: tasks,
    localCommand: './gradlew.bat :modules:alpha:test :modules:beta:test -PskipWebBuild=true --console=plain',
    advisoryBudgets: {
      maxSummedSuiteSeconds: 300,
      slowSuiteWarnSeconds: 60,
      maxSkipped: 50,
    },
    ...overrides,
  };
}

function policy(lanes = [lane()]) {
  return {
    kind: 'justsearch-unit-test-shard-policy.v1',
    version: 1,
    budgetsAreAdvisory: true,
    lanes,
  };
}

function workflow({ laneName = 'alpha', artifact = 'unit-test-attribution-alpha', runnerLabel = 'windows-latest/alpha', gradleTasks = tasks } = {}) {
  return [
    'name: CI',
    'jobs:',
    '  unit-tests:',
    '    name: Unit tests (${{ matrix.lane }})',
    '    strategy:',
    '      matrix:',
    '        include:',
    `          - lane: ${laneName}`,
    `            artifact: ${artifact}`,
    `            runner_label: ${runnerLabel}`,
    '            gradle_tasks: >-',
    ...gradleTasks.map((task) => `              ${task}`),
    '    steps:',
    '      - name: Test',
    '        run: ./gradlew.bat ${{ matrix.gradle_tasks }}',
    '  other:',
    '    runs-on: ubuntu-latest',
    '',
  ].join('\n');
}

function signalPolicy(checks = ['Unit tests (alpha)']) {
  return {
    workflows: [
      {
        name: 'CI',
        requiredStatusChecks: checks,
      },
    ],
  };
}

function validate({ policyDoc = policy(), workflowText = workflow(), signalDoc = signalPolicy() } = {}) {
  return validateUnitTestShardPolicy({
    policy: policyDoc,
    workflowText,
    workflowSignalPolicy: signalDoc,
  });
}

{
  const result = validate();
  assert.equal(result.ok, true, result.issues.join('\n'));
  assert.deepEqual(result.policyLanes, ['alpha']);
  assert.deepEqual(result.workflowLanes, ['alpha']);
}

{
  const result = validate({ workflowText: workflow({ laneName: 'beta' }) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /policy lane missing from workflow matrix: alpha/);
  assert.match(result.issues.join('\n'), /workflow matrix lane missing from policy: beta/);
}

{
  const result = validate({ workflowText: workflow({ artifact: 'wrong-artifact' }) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /artifact mismatch/);
}

{
  const result = validate({ workflowText: workflow({ runnerLabel: 'ubuntu-latest/alpha' }) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /runner label mismatch/);
}

{
  const result = validate({ workflowText: workflow({ gradleTasks: [':modules:alpha:test'] }) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /gradle task list differs/);
}

{
  const result = validate({ signalDoc: signalPolicy(['Other check']) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /required check missing/);
}

{
  const result = validate({ policyDoc: policy([lane({ platformClass: 'unknown-platform' })]) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /platformClass is not allowed/);
}

{
  const result = validate({ policyDoc: policy([lane({ platformRiskNotes: [] })]) });
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /platformRiskNotes must be a non-empty array/);
}

console.log('test-verify-unit-test-shard-policy: PASS');
