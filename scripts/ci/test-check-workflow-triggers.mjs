#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { validateWorkflows } from './check-workflow-triggers.mjs';

function write(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text, 'utf8');
}

function repoFixture() {
  const repoRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-workflow-triggers-'));
  write(path.join(repoRoot, 'settings.gradle.kts'), '');
  return repoRoot;
}

function policy() {
  return {
    kind: 'justsearch-workflow-signal-policy.v1',
    workflows: [
      {
        name: 'CI',
        path: '.github/workflows/ci.yml',
        expectedTriggers: ['workflow_dispatch', 'pull_request', 'push'],
      },
      {
        name: 'CLA Assistant',
        path: '.github/workflows/cla.yml',
        expectedTriggers: ['issue_comment', 'pull_request_target'],
      },
      {
        name: 'Docs lint',
        path: '.github/workflows/docs-lint.yml',
        expectedTriggers: ['workflow_dispatch'],
      },
      {
        name: 'Dependabot Updates',
        path: 'github-managed',
        expectedTriggers: ['dependabot'],
      },
    ],
  };
}

function writeBaseWorkflows(repoRoot) {
  write(
    path.join(repoRoot, '.github/workflows/ci.yml'),
    [
      'name: CI',
      'on:',
      '  workflow_dispatch: {}',
      '  pull_request:',
      '  push:',
      '    branches: [main]',
      'jobs: {}',
      '',
    ].join('\n')
  );
  write(
    path.join(repoRoot, '.github/workflows/cla.yml'),
    [
      'name: CLA Assistant',
      'on:',
      '  issue_comment:',
      '    types: [created]',
      '  pull_request_target:',
      '    types: [opened, synchronize]',
      'jobs: {}',
      '',
    ].join('\n')
  );
  write(
    path.join(repoRoot, '.github/workflows/docs-lint.yml'),
    ['name: Docs lint', '"on":', '  workflow_dispatch: {}', 'jobs: {}', ''].join('\n')
  );
}

{
  const repoRoot = repoFixture();
  writeBaseWorkflows(repoRoot);
  assert.deepEqual(validateWorkflows({ repoRoot, policy: policy() }), []);
}

{
  const repoRoot = repoFixture();
  writeBaseWorkflows(repoRoot);
  write(
    path.join(repoRoot, '.github/workflows/docs-lint.yml'),
    ['name: Docs lint', 'on:', '  workflow_dispatch: {}', '  push:', 'jobs: {}', ''].join('\n')
  );
  const errors = validateWorkflows({ repoRoot, policy: policy() });
  assert.equal(errors.length, 1);
  assert.match(errors[0].message, /unexpected trigger.*push/);
}

{
  const repoRoot = repoFixture();
  writeBaseWorkflows(repoRoot);
  write(path.join(repoRoot, '.github/workflows/unregistered.yml'), ['name: Surprise', 'on: push', 'jobs: {}', ''].join('\n'));
  const errors = validateWorkflows({ repoRoot, policy: policy() });
  assert.equal(errors.length, 1);
  assert.match(errors[0].message, /missing from workflow-signal-policy/);
}

{
  const repoRoot = repoFixture();
  writeBaseWorkflows(repoRoot);
  write(
    path.join(repoRoot, '.github/workflows/ci.yml'),
    ['name: CI', 'on:', '  workflow_dispatch: {}', '  pull_request:', 'jobs: {}', ''].join('\n')
  );
  const errors = validateWorkflows({ repoRoot, policy: policy() });
  assert.equal(errors.length, 1);
  assert.match(errors[0].message, /missing expected trigger.*push/);
}

{
  const repoRoot = repoFixture();
  writeBaseWorkflows(repoRoot);
  const p = policy();
  p.workflows.push({
    name: 'Missing workflow',
    path: '.github/workflows/missing.yml',
    expectedTriggers: ['workflow_dispatch'],
  });
  const errors = validateWorkflows({ repoRoot, policy: p });
  assert.equal(errors.length, 1);
  assert.match(errors[0].message, /points at a missing workflow file/);
}

console.log('test-check-workflow-triggers: PASS');
