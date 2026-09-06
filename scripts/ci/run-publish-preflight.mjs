#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { loadPublicCiLocalRepro } from './lib/public-ci-local-repro.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function usage() {
  return 'Usage: node scripts/ci/run-publish-preflight.mjs [--check|--list|--run]';
}

export function commandForPlatform(command, platform = process.platform) {
  if (platform !== 'win32') return command;
  return command.replace(/^\.\/gradlew\.bat(?=\s|$)/, '.\\gradlew.bat');
}

export function assertCleanCandidate({ cwd = ROOT, run = spawnSync } = {}) {
  const result = run(
    'git',
    ['status', '--porcelain=v1', '--untracked-files=all'],
    { cwd, encoding: 'utf8', windowsHide: true },
  );
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`git status failed while checking the publication candidate (exit ${result.status ?? 'unknown'})`);
  }
  if (String(result.stdout || '').trim()) {
    throw new Error(
      'publication preflight requires a clean candidate; commit all staged, unstaged, and non-ignored untracked files before --run',
    );
  }
}

export function executeLocalSubsets(manifest, {
  checkCandidate = assertCleanCandidate,
  cwd = ROOT,
  platform = process.platform,
  run = spawnSync,
} = {}) {
  checkCandidate({ cwd });
  for (const context of manifest.contexts) {
    if (context.mode !== 'local-subset') continue;
    process.stdout.write(`\n[${context.check}]\n`);
    for (const command of context.commands) {
      const executableCommand = commandForPlatform(command, platform);
      process.stdout.write(`> ${executableCommand}\n`);
      const result = run(executableCommand, { cwd, shell: true, stdio: 'inherit' });
      if (result.error) throw result.error;
      if (result.status !== 0) return result.status ?? 1;
    }
  }
  return 0;
}

function printInventory(manifest) {
  for (const context of manifest.contexts) {
    if (context.mode === 'hosted-only') console.log(`${context.check}: hosted-only — ${context.reason}`);
    else console.log(`${context.check}: local-subset (${context.commands.length} command${context.commands.length === 1 ? '' : 's'})`);
  }
}

function main() {
  const args = process.argv.slice(2);
  if (args.length > 1 || (args[0] && !['--check', '--list', '--run', '-h', '--help'].includes(args[0]))) {
    console.error(usage());
    process.exitCode = 2;
    return;
  }
  if (args[0] === '-h' || args[0] === '--help') {
    console.log(usage());
    return;
  }
  const manifest = loadPublicCiLocalRepro();
  if (args[0] === '--run') process.exitCode = executeLocalSubsets(manifest);
  else if (args[0] === '--list') printInventory(manifest);
  else console.log(`run-publish-preflight: PASS (${manifest.contexts.length} required checks classified)`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) main();
