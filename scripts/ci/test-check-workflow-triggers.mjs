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
    [
      'name: Docs lint',
      '"on":',
      '  workflow_dispatch: {}',
      'jobs:',
      '  docs_lint:',
      '    runs-on: [self-hosted, Windows, X64, justsearch-perf]',
      '',
    ].join('\n')
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

// Self-hosted + externally-triggerable event fails EVEN IF policy allows it
// (the both-files-edited-together evasion). Policy here expects the PR trigger,
// so the only error must be the hard self-hosted invariant.
{
  const repoRoot = repoFixture();
  writeBaseWorkflows(repoRoot);
  write(
    path.join(repoRoot, '.github/workflows/docs-lint.yml'),
    [
      'name: Docs lint',
      'on:',
      '  workflow_dispatch: {}',
      '  pull_request:',
      'jobs:',
      '  docs_lint:',
      '    runs-on: [self-hosted, Windows, X64, justsearch-perf]',
      '',
    ].join('\n')
  );
  const p = policy();
  p.workflows.find((w) => w.name === 'Docs lint').expectedTriggers = ['workflow_dispatch', 'pull_request'];
  const errors = validateWorkflows({ repoRoot, policy: p });
  assert.equal(errors.length, 1);
  assert.match(errors[0].message, /self-hosted runner job must not use externally-triggerable event 'pull_request'/);
}

// A hosted runner with a PR trigger is fine — the invariant is self-hosted-only.
{
  const repoRoot = repoFixture();
  writeBaseWorkflows(repoRoot);
  // ci.yml (hosted, pull_request) already in base workflows → no self-hosted error.
  const errors = validateWorkflows({ repoRoot, policy: policy() });
  assert.deepEqual(errors, []);
}

// A bare "self-hosted" mention in a comment must NOT trip the detector
// (ci-walltime-trend.yml's ADR-0026 note is a real example of this).
{
  const repoRoot = repoFixture();
  writeBaseWorkflows(repoRoot);
  write(
    path.join(repoRoot, '.github/workflows/docs-lint.yml'),
    [
      'name: Docs lint',
      '# see ADR-0026 for the self-hosted runner rationale',
      'on:',
      '  workflow_dispatch: {}',
      '  pull_request:',
      'jobs:',
      '  docs_lint:',
      '    runs-on: ubuntu-latest  # not self-hosted',
      '',
    ].join('\n')
  );
  const p = policy();
  p.workflows.find((w) => w.name === 'Docs lint').expectedTriggers = ['workflow_dispatch', 'pull_request'];
  const errors = validateWorkflows({ repoRoot, policy: p });
  assert.deepEqual(errors, [], 'comment mention of self-hosted must not trigger the hard invariant');
}

console.log('test-check-workflow-triggers: PASS');
