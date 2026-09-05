#!/usr/bin/env node

/**
 * Route ordinary agent CLI merge requests through the repository-owned publication
 * preflight. The hook is deliberately syntactic and side-effect free; live validation
 * belongs to `scripts/dev/run-gh.mjs enqueue`.
 */

import path from 'node:path';
import { readStdin, runHook } from '../lib/hook-base.mjs';

function shellSegments(command) {
  const segments = [];
  let current = '';
  let quote = null;
  for (let index = 0; index < command.length; index += 1) {
    const char = command[index];
    if (quote) {
      current += char;
      if (char === quote && command[index - 1] !== '\\') quote = null;
      continue;
    }
    if (char === '"' || char === "'") {
      quote = char;
      current += char;
      continue;
    }
    const pair = command.slice(index, index + 2);
    if (pair === '&&' || pair === '||') {
      segments.push(current);
      current = '';
      index += 1;
      continue;
    }
    if (char === ';' || char === '|' || char === '\n' || char === '\r') {
      segments.push(current);
      current = '';
      continue;
    }
    current += char;
  }
  segments.push(current);
  return segments;
}

function shellWords(segment) {
  const words = [];
  let current = '';
  let quote = null;
  for (let index = 0; index < segment.length; index += 1) {
    const char = segment[index];
    if (quote) {
      if (char === quote && segment[index - 1] !== '\\') quote = null;
      else current += char;
      continue;
    }
    if (char === '"' || char === "'") {
      quote = char;
      continue;
    }
    if (/\s/.test(char)) {
      if (current) words.push(current);
      current = '';
      continue;
    }
    current += char;
  }
  if (current) words.push(current);
  return words;
}

function executableName(value) {
  const windows = path.win32.basename(value).toLowerCase();
  return path.posix.basename(windows).toLowerCase();
}

function isGhExecutable(value) {
  const name = executableName(value);
  return name === 'gh' || name === 'gh.exe';
}

function isNodeExecutable(value) {
  const name = executableName(value);
  return name === 'node' || name === 'node.exe';
}

const GH_VALUE_FLAGS = new Set(['--repo', '-R', '--hostname', '--config-dir']);
const NODE_NON_SCRIPT_MODES = new Set(['--check', '-c', '--eval', '-e', '--print', '-p']);
const NODE_VALUE_FLAGS = new Set([
  '--conditions', '-C', '--cpu-prof-dir', '--diagnostic-dir', '--env-file',
  '--env-file-if-exists', '--heap-prof-dir', '--icu-data-dir', '--import',
  '--input-type', '--inspect-port', '--loader', '--experimental-loader', '--openssl-config', '--redirect-warnings',
  '--require', '-r', '--title', '--trace-event-categories', '--trace-event-file-pattern',
]);

function stripGhGlobalFlags(words) {
  const positional = [];
  for (let index = 0; index < words.length; index += 1) {
    const word = words[index];
    if (word === '--help' || word === '-h') return null;
    if (GH_VALUE_FLAGS.has(word)) {
      index += 1;
      if (index >= words.length) return [];
      continue;
    }
    if (word.startsWith('-R') && word.length > 2) continue;
    if ([...GH_VALUE_FLAGS].some((flag) => word.startsWith(`${flag}=`))) continue;
    positional.push(word);
  }
  return positional;
}

function runGhScriptIndex(words) {
  for (let index = 1; index < words.length;) {
    const word = words[index];
    if (executableName(word) === 'run-gh.mjs') return index;
    if (word === '--') return executableName(words[index + 1] ?? '') === 'run-gh.mjs' ? index + 1 : -1;
    if (NODE_NON_SCRIPT_MODES.has(word) || [...NODE_NON_SCRIPT_MODES].some((flag) => word.startsWith(`${flag}=`))) return -1;
    if (NODE_VALUE_FLAGS.has(word)) {
      index += 2;
      continue;
    }
    if ([...NODE_VALUE_FLAGS].some((flag) => word.startsWith(`${flag}=`))
      || (/^-[rC].+/.test(word))) {
      index += 1;
      continue;
    }
    if (word.startsWith('-')) {
      index += 1;
      continue;
    }
    return -1;
  }
  return -1;
}

export function directPublicationMerge(command) {
  if (typeof command !== 'string' || command.trim() === '') return false;
  for (const segment of shellSegments(command)) {
    const words = shellWords(segment);
    if (words[0] === '&') words.shift();
    if (isGhExecutable(words[0])) {
      const positional = stripGhGlobalFlags(words.slice(1));
      if (positional?.[0] === 'pr' && positional[1] === 'merge') return true;
    }
    if (isNodeExecutable(words[0])) {
      const scriptIndex = runGhScriptIndex(words);
      const args = scriptIndex >= 0 ? stripGhGlobalFlags(words.slice(scriptIndex + 1)) : null;
      if (args?.[0] === 'pr' && args[1] === 'merge') return true;
    }
  }
  return false;
}

async function main() {
  const raw = await readStdin();
  try {
    const input = JSON.parse(raw);
    if (input.hook_event_name !== 'PreToolUse') return;
    if (!directPublicationMerge(input.tool_input?.command)) return;
    process.stderr.write(
      'Direct PR merge requests bypass JustSearch publication validation. '
      + 'Run `node scripts/dev/run-gh.mjs enqueue <pr-number> [--repo owner/repo]`; '
      + 'it checks the live squash record and managed review record before entering the merge queue.',
    );
    process.exit(2);
  } catch {
    // Malformed hook input must not turn an unrelated command into a denial.
  }
}

runHook(import.meta.url, main);
