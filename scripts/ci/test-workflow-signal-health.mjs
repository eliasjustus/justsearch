#!/usr/bin/env node

import assert from 'node:assert/strict';

import { buildHealthReport, renderMarkdown } from './workflow-signal-health.mjs';

const policy = {
  workflows: [
    {
      name: 'CI',
      path: '.github/workflows/ci.yml',
      class: 'primary-manual-gate',
      owner: 'ADR-0026',
      failureDefault: 'product-regression',
      staleDays: 30,
    },
    {
      name: 'Build Installer',
      path: '.github/workflows/build-installer.yml',
      class: 'release-gate',
      owner: 'tempdoc 374',
      failureDefault: 'release-blocking-failure',
      staleDays: 14,
      blocking: true,
    },
    {
      name: 'Agent Live Eval Nightly',
      path: '.github/workflows/agent-live-eval-nightly.yml',
      class: 'scheduled-quality-signal',
      owner: 'agent quality',
      failureDefault: 'workflow-assumption-drift',
      staleDays: 7,
    },
    {
      name: 'Phase 3 Observability Nightly',
      path: '.github/workflows/phase-3-observability-nightly.yml',
      class: 'scheduled-quality-signal',
      owner: 'tempdocs 400/404',
      failureDefault: 'workflow-assumption-drift',
      staleDays: 7,
    },
  ],
};

function run(id, workflowName, conclusion, updatedAt, event = 'workflow_dispatch') {
  return {
    databaseId: id,
    workflowName,
    name: workflowName,
    conclusion,
    status: 'completed',
    event,
    headBranch: 'main',
    headSha: `sha-${id}`,
    updatedAt,
    url: `https://example.test/runs/${id}`,
  };
}

function inProgressRun(id, workflowName, updatedAt, event = 'workflow_dispatch') {
  return {
    ...run(id, workflowName, null, updatedAt, event),
    status: 'in_progress',
  };
}

function jobs(...stepNames) {
  return {
    jobs: [
      {
        name: 'job',
        steps: stepNames.map((name) => ({ name, conclusion: 'failure' })),
      },
    ],
  };
}

function find(report, name) {
  return report.workflows.find((wf) => wf.name === name);
}

const now = new Date('2026-04-24T12:00:00.000Z');

{
  const report = buildHealthReport({
    policy,
    now,
    runs: [
      run(1, 'CI', 'success', '2026-04-24T11:00:00.000Z', 'workflow_dispatch'),
      run(2, 'Build Installer', 'success', '2026-04-24T10:00:00.000Z'),
      run(3, 'Agent Live Eval Nightly', 'success', '2026-04-24T09:00:00.000Z'),
      run(4, 'Phase 3 Observability Nightly', 'success', '2026-04-24T08:00:00.000Z'),
    ],
  });
  assert.equal(find(report, 'CI').failureClass, 'passed');
  assert.match(renderMarkdown(report), /Agent Live Eval Nightly/);
}

{
  const report = buildHealthReport({
    policy,
    now,
    runs: [run(10, 'Phase 3 Observability Nightly', 'failure', '2026-04-24T08:00:00.000Z')],
    jobsByRunId: new Map([['10', jobs('Install jseval Python deps')]]),
  });
  assert.equal(find(report, 'Phase 3 Observability Nightly').failureClass, 'infra-drift');
}

{
  const report = buildHealthReport({
    policy,
    now,
    runs: [run(11, 'Phase 3 Observability Nightly', 'failure', '2026-04-24T08:00:00.000Z')],
    jobsByRunId: new Map([['11', jobs('jseval gate')]]),
  });
  assert.equal(find(report, 'Phase 3 Observability Nightly').failureClass, 'product-regression');
}

{
  const report = buildHealthReport({
    policy,
    now,
    runs: [run(12, 'Agent Live Eval Nightly', 'failure', '2026-04-24T08:00:00.000Z')],
    jobsByRunId: new Map([['12', jobs('Run live battery', 'Publish summary')]]),
  });
  const agent = find(report, 'Agent Live Eval Nightly');
  assert.equal(agent.failureClass, 'product-regression');
  assert.deepEqual(agent.failedSteps, ['Run live battery', 'Publish summary']);
}

{
  const report = buildHealthReport({
    policy,
    now,
    runs: [run(20, 'CI', 'success', '2026-03-01T00:00:00.000Z', 'workflow_dispatch')],
  });
  assert.equal(find(report, 'CI').failureClass, 'stale-or-zombie');
}

{
  const report = buildHealthReport({
    policy,
    now,
    runs: [run(30, 'Build Installer', 'failure', '2026-04-24T08:00:00.000Z')],
    jobsByRunId: new Map([['30', jobs('Package installer')]]),
  });
  assert.equal(find(report, 'Build Installer').failureClass, 'release-blocking-failure');
  assert.equal(find(report, 'Build Installer').blocking, true);
}

{
  const report = buildHealthReport({
    policy,
    now,
    runs: [inProgressRun(31, 'Build Installer', '2026-04-24T11:00:00.000Z')],
  });
  assert.equal(find(report, 'Build Installer').failureClass, 'in-progress');
}

{
  const report = buildHealthReport({
    policy,
    now,
    runs: [run(40, 'CI', 'failure', '2026-04-24T08:00:00.000Z', 'workflow_dispatch')],
    jobsByRunId: new Map([['40', jobs('Stress suite coverage gate')]]),
  });
  assert.equal(find(report, 'CI').failureClass, 'workflow-assumption-drift');
}

{
  const report = buildHealthReport({
    policy,
    now,
    runs: [run(41, 'CI', 'failure', '2026-04-24T08:00:00.000Z', 'workflow_dispatch')],
    jobsByRunId: new Map([['41', jobs('Run stress tests')]]),
  });
  assert.equal(find(report, 'CI').failureClass, 'product-regression');
}

console.log('test-workflow-signal-health: PASS');
