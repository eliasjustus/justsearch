#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function parseArgs(argv) {
  const out = {
    check: false,
    tasks: false,
    json: false,
    root: null,
    policy: null,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--check') out.check = true;
    else if (arg === '--tasks') out.tasks = true;
    else if (arg === '--json') out.json = true;
    else if (arg === '--root' && argv[i + 1]) out.root = argv[++i];
    else if (arg === '--policy' && argv[i + 1]) out.policy = argv[++i];
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  return out;
}

function usage() {
  return [
    'Usage: node scripts/ci/verify-stress-suite-policy.mjs [--check|--tasks|--json] [--root DIR] [--policy FILE]',
    '',
    'Verifies that every Java @Tag("stress") test under modules/**/src/test is covered by',
    'scripts/ci/stress-suite-policy.v1.json, and that every policy entry still has tests.',
  ].join('\n');
}

function toRepoPath(root, absPath) {
  return path.relative(root, absPath).split(path.sep).join('/');
}

function readJavaString(text, start) {
  let i = start + 1;
  let value = '';
  while (i < text.length) {
    const ch = text[i];
    if (ch === '\\') {
      i += 2;
      continue;
    }
    if (ch === '"') return { value, end: i + 1 };
    value += ch;
    i += 1;
  }
  return null;
}

function hasStressTag(text) {
  let i = 0;
  let mode = 'code';
  while (i < text.length) {
    const ch = text[i];
    const next = text[i + 1];
    if (mode === 'code') {
      if (ch === '/' && next === '/') {
        mode = 'line';
        i += 2;
      } else if (ch === '/' && next === '*') {
        mode = 'block';
        i += 2;
      } else if (ch === '"') {
        const str = readJavaString(text, i);
        i = str?.end ?? i + 1;
      } else if (ch === "'") {
        i += 1;
        while (i < text.length) {
          if (text[i] === '\\') i += 2;
          else if (text[i] === "'") {
            i += 1;
            break;
          } else i += 1;
        }
      } else if (text.startsWith('@Tag', i)) {
        let j = i + '@Tag'.length;
        while (/\s/.test(text[j] || '')) j += 1;
        if (text[j] !== '(') {
          i += 1;
          continue;
        }
        j += 1;
        while (/\s/.test(text[j] || '')) j += 1;
        if (text[j] !== '"') {
          i += 1;
          continue;
        }
        const str = readJavaString(text, j);
        if (str?.value === 'stress') return true;
        i = str?.end ?? i + 1;
      } else {
        i += 1;
      }
    } else if (mode === 'line') {
      if (ch === '\n' || ch === '\r') {
        mode = 'code';
      }
      i += 1;
    } else if (mode === 'block') {
      if (ch === '*' && next === '/') {
        mode = 'code';
        i += 2;
      } else {
        i += 1;
      }
    }
  }
  return false;
}

function walkJavaTests(root, dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const abs = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'build' || entry.name === '.gradle' || entry.name === 'target') continue;
      walkJavaTests(root, abs, out);
    } else if (entry.isFile() && entry.name.endsWith('.java')) {
      const rel = toRepoPath(root, abs);
      if (rel.startsWith('modules/') && rel.includes('/src/test/')) out.push(abs);
    }
  }
  return out;
}

function modulePathFor(repoPath) {
  const parts = repoPath.split('/');
  if (parts.length < 2 || parts[0] !== 'modules') return null;
  return `${parts[0]}/${parts[1]}`;
}

export function discoverStressTests(root) {
  const modulesDir = path.join(root, 'modules');
  const discovered = new Map();
  for (const abs of walkJavaTests(root, modulesDir)) {
    const raw = fs.readFileSync(abs, 'utf8');
    if (!hasStressTag(raw)) continue;
    const repoPath = toRepoPath(root, abs);
    const modulePath = modulePathFor(repoPath);
    if (!modulePath) continue;
    const tests = discovered.get(modulePath) || [];
    tests.push(repoPath);
    discovered.set(modulePath, tests);
  }
  for (const tests of discovered.values()) tests.sort();
  return discovered;
}

export function validateStressSuitePolicy(policy, discovered) {
  const issues = [];
  if (policy.kind !== 'justsearch-stress-suite-policy.v1') {
    issues.push(`policy kind must be justsearch-stress-suite-policy.v1, got ${policy.kind}`);
  }
  if (policy.version !== 1) issues.push(`policy version must be 1, got ${policy.version}`);
  if (!Array.isArray(policy.modules)) issues.push('policy modules must be an array');
  const modules = Array.isArray(policy.modules) ? policy.modules : [];
  const byModule = new Map();
  const seenTasks = new Set();
  for (const entry of modules) {
    if (!entry.modulePath) issues.push('policy entry missing modulePath');
    if (!entry.gradleTask) issues.push(`policy entry ${entry.modulePath || '<unknown>'} missing gradleTask`);
    if (!entry.owner) issues.push(`policy entry ${entry.modulePath || '<unknown>'} missing owner`);
    if (!entry.reason) issues.push(`policy entry ${entry.modulePath || '<unknown>'} missing reason`);
    if (!Array.isArray(entry.expectedStressTests)) {
      issues.push(`policy entry ${entry.modulePath || '<unknown>'} expectedStressTests must be an array`);
    }
    if (entry.modulePath) {
      if (byModule.has(entry.modulePath)) issues.push(`duplicate policy module ${entry.modulePath}`);
      byModule.set(entry.modulePath, entry);
    }
    if (entry.gradleTask) {
      if (seenTasks.has(entry.gradleTask)) issues.push(`duplicate policy gradleTask ${entry.gradleTask}`);
      seenTasks.add(entry.gradleTask);
    }
  }

  for (const [modulePath, tests] of discovered.entries()) {
    const entry = byModule.get(modulePath);
    if (!entry) {
      issues.push(`stress tests in ${modulePath} are missing from policy: ${tests.join(', ')}`);
      continue;
    }
    const expected = new Set(entry.expectedStressTests || []);
    for (const testPath of tests) {
      if (!expected.has(testPath)) {
        issues.push(`stress test ${testPath} is not listed in policy entry ${modulePath}`);
      }
    }
  }

  for (const entry of modules) {
    const actual = new Set(discovered.get(entry.modulePath) || []);
    if (actual.size === 0) {
      issues.push(`policy module ${entry.modulePath} has no discovered @Tag("stress") tests`);
    }
    for (const expectedPath of entry.expectedStressTests || []) {
      if (!actual.has(expectedPath)) {
        issues.push(`policy expected stress test missing or untagged: ${expectedPath}`);
      }
    }
  }

  return {
    ok: issues.length === 0,
    issues,
    discovered: [...discovered.entries()].map(([modulePath, tests]) => ({ modulePath, tests })),
    tasks: modules.map((entry) => entry.gradleTask).filter(Boolean),
  };
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log(usage());
    return;
  }
  const root = path.resolve(opts.root || repoRootFromCwd());
  const policyPath = path.resolve(
    opts.policy || path.join(root, 'scripts', 'ci', 'stress-suite-policy.v1.json'),
  );
  const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
  const result = validateStressSuitePolicy(policy, discoverStressTests(root));
  if (!result.ok) {
    if (opts.json) console.log(JSON.stringify(result, null, 2));
    else {
      console.error('verify-stress-suite-policy: FAIL');
      for (const issue of result.issues) console.error(`- ${issue}`);
    }
    process.exitCode = 1;
    return;
  }
  if (opts.json) console.log(JSON.stringify(result, null, 2));
  else if (opts.tasks) console.log(result.tasks.join(' '));
  else console.log(`verify-stress-suite-policy: OK (${result.tasks.length} modules)`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
