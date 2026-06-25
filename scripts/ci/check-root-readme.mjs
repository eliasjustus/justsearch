#!/usr/bin/env node
/**
 * Guard the root README as the repository entry point.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

function repoRootFromCwd() {
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

const REQUIRED_PATTERNS = [
  { name: 'local-first positioning', pattern: /local-first/i },
  { name: 'architecture link', pattern: /docs\/explanation\/01-system-overview\.md/i },
  { name: 'API contract link', pattern: /docs\/reference\/api-contract-map\.md/i },
  { name: 'contributing link', pattern: /CONTRIBUTING\.md/ },
  { name: 'security link', pattern: /SECURITY\.md/ },
  { name: 'license link', pattern: /LICENSE/ },
];

function main() {
  const repoRoot = repoRootFromCwd();
  const readmePath = path.join(repoRoot, 'README.md');
  if (!fs.existsSync(readmePath)) {
    console.error('check-root-readme: FAIL');
    console.error('README.md is missing.');
    process.exitCode = 1;
    return;
  }

  const readme = fs.readFileSync(readmePath, 'utf8');
  const firstContentLine = readme.split(/\r?\n/).find((line) => line.trim().length > 0) ?? '';
  const errors = [];

  if (firstContentLine.trim() !== '# JustSearch') {
    errors.push("README.md must start with '# JustSearch'.");
  }

  if (/^#\s+LocalIntentTranslatorConfig Dead Code Analysis\s*$/m.test(readme)) {
    errors.push('README.md still contains the old dead-code-analysis title as a top-level heading.');
  }

  for (const required of REQUIRED_PATTERNS) {
    if (!required.pattern.test(readme)) {
      errors.push(`README.md is missing ${required.name}.`);
    }
  }

  if (errors.length === 0) {
    console.log('check-root-readme: OK');
    return;
  }

  console.error('check-root-readme: FAIL');
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exitCode = 1;
}

main();
