#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildReport, renderMarkdown } from './report-build-attribution.mjs';

const scriptPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'report-build-attribution.mjs');

function withTempRoot(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-build-attribution-'));
  try {
    return fn(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function task(pathName, durationMillis, extra = {}) {
  return {
    path: pathName,
    displayName: `Task ${pathName}`,
    outcome: extra.outcome || 'success',
    startTimeMillis: 1000,
    endTimeMillis: 1000 + durationMillis,
    durationMillis,
    skipped: Boolean(extra.skipped),
    upToDate: Boolean(extra.upToDate),
    fromCache: Boolean(extra.fromCache),
    failureCount: extra.failureCount || 0,
  };
}

const taskTiming = {
  kind: 'justsearch-build-task-timing.v1',
  generatedAt: '2026-06-29T09:00:00.000Z',
  rootDir: 'F:/justsearch-public',
  tasks: [
    task(':modules:ui:installWebDependencies', 10_000),
    task(':modules:ui:buildWeb', 2_500),
    task(':modules:ui:copyWebResources', 400),
    task(':modules:ui:compileJava', 200, { fromCache: true }),
    task(':modules:ui:classes', 50, { upToDate: true }),
    task(':modules:ui:distZip', 7_000),
    task(':modules:core:compileJava', 3_000),
  ],
};

{
  const report = buildReport({
    command: ['./gradlew.bat', 'assemble', '-PskipWebBuild=false', '--console=plain'],
    exitCode: 0,
    startedAt: '2026-06-29T09:00:00.000Z',
    completedAt: '2026-06-29T09:01:00.000Z',
    taskTiming,
    taskTimingFile: 'build/ci/build-task-timing.json',
    runner: {
      runnerLabel: 'windows-latest',
      runnerOs: 'Windows',
      runnerArch: 'X64',
      runnerName: 'GitHub Actions 1',
      imageOs: 'windows-2025-vs2026',
      imageVersion: '20260622.153.1',
    },
    top: 3,
    env: { GITHUB_ACTIONS: 'true', GRADLE_USER_HOME: 'C:/Users/runneradmin/.gradle' },
  });

  assert.equal(report.kind, 'justsearch-build-attribution.v1');
  assert.equal(report.success, true);
  assert.equal(report.taskEvidencePresent, true);
  assert.deepEqual(report.requestedGradleTasks, ['assemble']);
  assert.equal(report.gradleProperties.find((entry) => entry.name === 'skipWebBuild').value, 'false');
  assert.equal(report.totals.tasks, 7);
  assert.equal(report.totals.fromCache, 1);
  assert.equal(report.phaseGroups[0].id, 'web-dependency-install');
  assert.equal(report.phaseGroups.some((entry) => entry.id === 'distribution-runtime-staging'), true);
  assert.equal(report.slowestTasks[0].path, ':modules:ui:installWebDependencies');
  assert.equal(report.cacheContext.githubActions, true);
  const md = renderMarkdown(report);
  assert.match(md, /Build attribution/);
  assert.match(md, /Web dependency install/);
  assert.match(md, /windows-2025-vs2026/);
}

{
  const report = buildReport({
    command: ['./gradlew.bat', 'assemble', '-PskipWebBuild=false'],
    exitCode: 1,
    startedAt: '2026-06-29T09:00:00.000Z',
    completedAt: '2026-06-29T09:00:05.000Z',
    taskTiming: null,
    taskTimingFile: 'build/ci/missing.json',
    runner: { runnerLabel: null, runnerOs: null, imageOs: null, imageVersion: null },
    env: {},
  });

  assert.equal(report.success, false);
  assert.equal(report.taskEvidencePresent, false);
  assert.match(report.warnings.join('\n'), /taskEvidencePresent=false/);
  assert.equal(report.runner.runnerLabel, null);
}

{
  const report = buildReport({
    command: ['./gradlew.bat', ':modules:ui:assemble', '-PskipWebBuild=false'],
    exitCode: 0,
    startedAt: '2026-06-29T09:00:00.000Z',
    completedAt: '2026-06-29T09:00:05.000Z',
    taskTiming: { kind: 'not-build-task-timing.v1', tasks: [] },
    taskTimingFile: 'bad.json',
    runner: { runnerLabel: 'windows-latest' },
    env: {},
  });

  assert.equal(report.taskEvidencePresent, false);
  assert.match(report.warnings.join('\n'), /ignored task timing payload/);
}

withTempRoot((root) => {
  const timingJson = path.join(root, 'build-task-timing.json');
  const outJson = path.join(root, 'build-attribution.json');
  const outMd = path.join(root, 'build-attribution.md');
  const summary = path.join(root, 'summary.md');
  writeJson(timingJson, taskTiming);

  const res = spawnSync(process.execPath, [
    scriptPath,
    '--task-timing-json', timingJson,
    '--out-json', outJson,
    '--out-md', outMd,
    '--runner-label', 'windows-latest',
    '--json',
    '--',
    process.execPath,
    '-e',
    'process.exit(7)',
  ], {
    encoding: 'utf8',
    env: { ...process.env, GITHUB_STEP_SUMMARY: summary, RUNNER_OS: 'Windows', ImageOS: 'win25-vs2026' },
  });

  assert.equal(res.status, 7, res.stderr);
  const report = JSON.parse(fs.readFileSync(outJson, 'utf8'));
  assert.equal(report.exitCode, 7);
  assert.equal(report.taskEvidencePresent, true);
  assert.match(fs.readFileSync(outMd, 'utf8'), /exit 7/);
  assert.match(fs.readFileSync(summary, 'utf8'), /Build attribution/);
  assert.equal(JSON.parse(res.stdout).runner.imageOs, 'win25-vs2026');
});

console.log('test-report-build-attribution: PASS');
