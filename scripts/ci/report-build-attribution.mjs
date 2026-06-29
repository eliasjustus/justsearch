#!/usr/bin/env node
/**
 * Run a build command and render advisory build-attribution evidence.
 *
 * The wrapped command remains the pass/fail authority. This script preserves
 * the command exit code while emitting JSON/Markdown evidence when possible.
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const KIND = 'justsearch-build-attribution.v1';
const TASK_TIMING_KIND = 'justsearch-build-task-timing.v1';
const DEFAULT_TOP = 20;

function usage() {
  return [
    'Usage: node scripts/ci/report-build-attribution.mjs [options] -- <build command>',
    '',
    'Options:',
    '  --task-timing-json <path>  Raw Gradle task timing evidence path',
    '  --runner-label <label>     Workflow runner label, e.g. windows-latest',
    '  --top <n>                  Number of slow tasks to include (default: 20)',
    '  --out-json <path>          Write JSON report',
    '  --out-md <path>            Write Markdown report',
    '  --json                     Print JSON to stdout after the command',
    '  --md                       Print Markdown to stdout after the command',
    '  -h, --help',
  ].join('\n');
}

function parseArgs(argv) {
  const separator = argv.indexOf('--');
  const optionArgs = separator === -1 ? argv : argv.slice(0, separator);
  const command = separator === -1 ? [] : argv.slice(separator + 1);
  const opts = {
    taskTimingJson: null,
    runnerLabel: null,
    top: DEFAULT_TOP,
    outJson: null,
    outMd: null,
    json: false,
    md: false,
    help: false,
    command,
  };

  for (let i = 0; i < optionArgs.length; i += 1) {
    const arg = optionArgs[i];
    if (arg === '--task-timing-json' && optionArgs[i + 1]) opts.taskTimingJson = optionArgs[++i];
    else if (arg === '--runner-label' && optionArgs[i + 1]) opts.runnerLabel = optionArgs[++i];
    else if (arg === '--top' && optionArgs[i + 1]) opts.top = Number.parseInt(optionArgs[++i], 10);
    else if (arg === '--out-json' && optionArgs[i + 1]) opts.outJson = optionArgs[++i];
    else if (arg === '--out-md' && optionArgs[i + 1]) opts.outMd = optionArgs[++i];
    else if (arg === '--json') opts.json = true;
    else if (arg === '--md') opts.md = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }

  if (!Number.isInteger(opts.top) || opts.top <= 0) throw new Error('--top must be a positive integer');
  if (!opts.help && opts.command.length === 0) throw new Error('missing build command after --');
  return opts;
}

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function writeText(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

function loadJsonIfPresent(filePath) {
  if (!filePath || !fs.existsSync(filePath)) return null;
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function isGradleCommand(command) {
  if (!command) return false;
  const base = path.basename(command).toLowerCase();
  return base === 'gradle' || base === 'gradle.bat' || base === 'gradlew' || base === 'gradlew.bat';
}

function withGradleAttributionProperty(command, taskTimingJson) {
  if (!taskTimingJson || !isGradleCommand(command[0])) return command;
  if (command.some((arg) => arg.startsWith('-PjustsearchBuildAttributionTasksJson='))) return command;
  return [...command, `-PjustsearchBuildAttributionTasksJson=${path.resolve(taskTimingJson)}`];
}

function runnerInfo(opts, env = process.env) {
  return {
    runnerLabel: opts.runnerLabel || null,
    runnerOs: env.RUNNER_OS || null,
    runnerArch: env.RUNNER_ARCH || null,
    runnerName: env.RUNNER_NAME || null,
    imageOs: env.ImageOS || null,
    imageVersion: env.ImageVersion || null,
    githubActions: String(env.GITHUB_ACTIONS || '').toLowerCase() === 'true',
  };
}

function parseGradleProperties(command) {
  return command
    .filter((arg) => arg.startsWith('-P') && arg.length > 2)
    .map((arg) => {
      const text = arg.slice(2);
      const index = text.indexOf('=');
      return index === -1
        ? { name: text, value: true }
        : { name: text.slice(0, index), value: text.slice(index + 1) };
    });
}

function parseRequestedGradleTasks(command) {
  if (!isGradleCommand(command[0])) return [];
  return command
    .slice(1)
    .filter((arg) => !arg.startsWith('-'))
    .filter((arg) => !arg.includes('='));
}

function normalizeTask(task) {
  const durationMillis = Number(task.durationMillis);
  return {
    path: String(task.path || '<unknown>'),
    displayName: String(task.displayName || task.path || '<unknown>'),
    outcome: String(task.outcome || '<unknown>'),
    startTimeMillis: Number(task.startTimeMillis || 0),
    endTimeMillis: Number(task.endTimeMillis || 0),
    durationMillis: Number.isFinite(durationMillis) ? durationMillis : 0,
    skipped: Boolean(task.skipped),
    upToDate: Boolean(task.upToDate),
    fromCache: Boolean(task.fromCache),
    failureCount: Number(task.failureCount || 0),
  };
}

function taskPhase(taskPath) {
  if (/:installWebDependencies$/i.test(taskPath)) return ['web-dependency-install', 'Web dependency install'];
  if (/:buildWeb$/i.test(taskPath)) return ['web-build', 'Web build'];
  if (/:copyWebResources$|:processResources$|:syncSsotSchemas$/i.test(taskPath)) return ['resource-copy', 'Resource copy'];
  if (/:compile(?:Java|Kotlin)$|:classes$|:jar$/i.test(taskPath)) return ['java-compile-classes', 'Java compile/classes'];
  if (/:distZip$|:distTar$|:installDist$|:startScripts$|headless|runtime|jlink|bundle|stage/i.test(taskPath)) {
    return ['distribution-runtime-staging', 'Distribution/runtime staging'];
  }
  return ['other-tasks', 'Other tasks'];
}

function summarizeTasks(tasks, top) {
  const totals = {
    tasks: tasks.length,
    skipped: 0,
    upToDate: 0,
    fromCache: 0,
    failed: 0,
    durationMillis: 0,
  };
  const groups = new Map();

  for (const task of tasks) {
    totals.durationMillis += task.durationMillis;
    if (task.skipped) totals.skipped += 1;
    if (task.upToDate) totals.upToDate += 1;
    if (task.fromCache) totals.fromCache += 1;
    if (task.outcome === 'failure' || task.failureCount > 0) totals.failed += 1;

    const [id, name] = taskPhase(task.path);
    const current = groups.get(id) || {
      id,
      name,
      tasks: 0,
      skipped: 0,
      upToDate: 0,
      fromCache: 0,
      failed: 0,
      durationMillis: 0,
    };
    current.tasks += 1;
    current.durationMillis += task.durationMillis;
    if (task.skipped) current.skipped += 1;
    if (task.upToDate) current.upToDate += 1;
    if (task.fromCache) current.fromCache += 1;
    if (task.outcome === 'failure' || task.failureCount > 0) current.failed += 1;
    groups.set(id, current);
  }

  return {
    totals,
    phaseGroups: [...groups.values()].sort((a, b) => b.durationMillis - a.durationMillis || a.id.localeCompare(b.id)),
    slowestTasks: [...tasks]
      .sort((a, b) => b.durationMillis - a.durationMillis || a.path.localeCompare(b.path))
      .slice(0, top),
  };
}

function cacheContext(env = process.env) {
  return {
    githubActions: String(env.GITHUB_ACTIONS || '').toLowerCase() === 'true',
    gradleUserHomeSet: Boolean(env.GRADLE_USER_HOME),
    npmConfigCacheSet: Boolean(env.npm_config_cache || env.NPM_CONFIG_CACHE),
  };
}

function formatCommand(command) {
  return command.map((part) => (/\s/.test(part) ? `"${part.replaceAll('"', '\\"')}"` : part)).join(' ');
}

function quoteCmdArg(arg) {
  const normalized = String(arg).replace(/^\.\//, '.\\');
  if (!/[ \t&()^;!'+,`~[\]{}]/.test(normalized)) return normalized;
  return `"${normalized.replaceAll('"', '\\"')}"`;
}

function fmtSeconds(ms) {
  const seconds = Math.round(ms / 1000);
  if (seconds >= 3600) return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
  if (seconds >= 60) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  return `${seconds}s`;
}

export function buildReport({
  command,
  exitCode,
  startedAt,
  completedAt,
  taskTiming,
  taskTimingFile = null,
  runner,
  top = DEFAULT_TOP,
  env = process.env,
}) {
  const warnings = [];
  let tasks = [];
  let taskEvidencePresent = false;
  let taskTimingKind = null;

  if (taskTiming && taskTiming.kind === TASK_TIMING_KIND && Array.isArray(taskTiming.tasks)) {
    taskEvidencePresent = true;
    taskTimingKind = taskTiming.kind;
    tasks = taskTiming.tasks.map(normalizeTask);
  } else if (taskTiming) {
    taskTimingKind = taskTiming.kind || null;
    warnings.push(`ignored task timing payload with kind ${taskTimingKind || '<missing>'}`);
  } else {
    warnings.push('taskEvidencePresent=false; no Gradle task timing evidence was produced');
  }

  const taskSummary = summarizeTasks(tasks, top);
  const startMs = new Date(startedAt).getTime();
  const endMs = new Date(completedAt).getTime();
  const durationMillis = Number.isFinite(startMs) && Number.isFinite(endMs) ? Math.max(0, endMs - startMs) : null;

  return {
    kind: KIND,
    generatedAt: new Date().toISOString(),
    command,
    commandLine: formatCommand(command),
    requestedGradleTasks: parseRequestedGradleTasks(command),
    gradleProperties: parseGradleProperties(command),
    exitCode,
    success: exitCode === 0,
    startedAt,
    completedAt,
    durationMillis,
    runner,
    cacheContext: cacheContext(env),
    taskEvidencePresent,
    taskTiming: {
      file: taskTimingFile,
      kind: taskTimingKind,
      taskCount: tasks.length,
    },
    totals: taskSummary.totals,
    phaseGroups: taskSummary.phaseGroups,
    slowestTasks: taskSummary.slowestTasks,
    warnings,
  };
}

export function renderMarkdown(report) {
  const lines = [
    '### Build attribution',
    '',
    `Generated: ${report.generatedAt}`,
    '',
    `Command: \`${report.commandLine}\``,
    `Result: ${report.success ? 'success' : 'failure'} (exit ${report.exitCode})`,
    `Duration: ${report.durationMillis == null ? 'unknown' : fmtSeconds(report.durationMillis)}`,
    `Runner: ${report.runner.runnerLabel || 'unknown label'} / ${report.runner.runnerOs || 'unknown OS'} / image ${report.runner.imageOs || 'unknown'} ${report.runner.imageVersion || ''}`.trim(),
    `Task evidence: ${report.taskEvidencePresent ? `${report.totals.tasks} tasks` : 'not available'}`,
    '',
  ];

  if (report.requestedGradleTasks.length > 0) {
    lines.push(`Requested Gradle tasks: ${report.requestedGradleTasks.map((task) => `\`${task}\``).join(', ')}`, '');
  }

  if (report.phaseGroups.length > 0) {
    lines.push('| Phase | Tasks | Skipped | From cache | Up to date | Failed | Task time |');
    lines.push('|---|---:|---:|---:|---:|---:|---:|');
    for (const group of report.phaseGroups) {
      lines.push(`| ${group.name} | ${group.tasks} | ${group.skipped} | ${group.fromCache} | ${group.upToDate} | ${group.failed} | ${fmtSeconds(group.durationMillis)} |`);
    }
    lines.push('');
  }

  if (report.slowestTasks.length > 0) {
    lines.push('| Slow task | Outcome | Skipped | From cache | Up to date | Duration |');
    lines.push('|---|---|---:|---:|---:|---:|');
    for (const task of report.slowestTasks) {
      lines.push(`| \`${task.path}\` | ${task.outcome} | ${task.skipped ? 1 : 0} | ${task.fromCache ? 1 : 0} | ${task.upToDate ? 1 : 0} | ${fmtSeconds(task.durationMillis)} |`);
    }
    lines.push('');
  }

  if (report.warnings.length > 0) {
    lines.push('Warnings:');
    for (const warning of report.warnings) lines.push(`- ${warning}`);
    lines.push('');
  }

  return `${lines.join('\n')}\n`;
}

function runCommand(command) {
  if (process.platform === 'win32' && /\.bat$/i.test(command[0])) {
    return spawnSync('cmd.exe', ['/d', '/s', '/c', command.map(quoteCmdArg).join(' ')], {
      stdio: 'inherit',
      windowsHide: true,
    });
  }
  return spawnSync(command[0], command.slice(1), {
    stdio: 'inherit',
    windowsHide: true,
  });
}

function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }
  } catch (error) {
    console.error(`report-build-attribution: ${error.message}`);
    console.error(usage());
    process.exitCode = 1;
    return;
  }

  const repoRoot = repoRootFromCwd();
  const taskTimingPath = opts.taskTimingJson
    ? path.resolve(opts.taskTimingJson)
    : path.join(repoRoot, 'build', 'ci', 'build-task-timing.json');
  const command = withGradleAttributionProperty(opts.command, taskTimingPath);
  const startedAt = new Date().toISOString();
  const result = runCommand(command);
  const completedAt = new Date().toISOString();
  const exitCode = result.status ?? (result.error ? 1 : 1);

  let taskTiming = null;
  try {
    taskTiming = loadJsonIfPresent(taskTimingPath);
  } catch (error) {
    taskTiming = { kind: 'unreadable-task-timing', error: error.message };
  }

  const report = buildReport({
    command,
    exitCode,
    startedAt,
    completedAt,
    taskTiming,
    taskTimingFile: taskTimingPath,
    runner: runnerInfo(opts),
    top: opts.top,
  });
  const json = `${JSON.stringify(report, null, 2)}\n`;
  const md = renderMarkdown(report);

  if (opts.outJson) writeText(path.resolve(opts.outJson), json);
  if (opts.outMd) writeText(path.resolve(opts.outMd), md);
  if (process.env.GITHUB_STEP_SUMMARY) {
    try {
      fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, md, 'utf8');
    } catch {
      // Best effort only.
    }
  }
  if (opts.json) process.stdout.write(json);
  else if (opts.md || (!opts.outJson && !opts.outMd)) process.stdout.write(md);

  if (result.error) {
    console.error(`report-build-attribution: failed to spawn build command: ${result.error.message}`);
  }
  process.exitCode = exitCode;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
