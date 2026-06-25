#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { discoverStressTests, validateStressSuitePolicy } from './verify-stress-suite-policy.mjs';

function write(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

function policy(entries) {
  return {
    kind: 'justsearch-stress-suite-policy.v1',
    version: 1,
    modules: entries,
  };
}

function entry(modulePath, tests) {
  return {
    modulePath,
    gradleTask: `:${modulePath.replace('/', ':')}:test`,
    owner: 'test',
    reason: 'test fixture',
    expectedStressTests: tests,
  };
}

function withTempRepo(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-stress-policy-'));
  try {
    fs.writeFileSync(path.join(root, 'settings.gradle.kts'), 'rootProject.name = "test"\n', 'utf8');
    return fn(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

withTempRepo((root) => {
  const testPath = 'modules/alpha/src/test/java/example/AlphaStressTest.java';
  write(path.join(root, testPath), [
    'package example;',
    'import org.junit.jupiter.api.Tag;',
    '@Tag("stress")',
    'class AlphaStressTest {}',
    '',
  ].join('\n'));
  const result = validateStressSuitePolicy(policy([entry('modules/alpha', [testPath])]), discoverStressTests(root));
  assert.equal(result.ok, true, result.issues.join('\n'));
  assert.deepEqual(result.tasks, [':modules:alpha:test']);
});

withTempRepo((root) => {
  const testPath = 'modules/beta/src/test/java/example/BetaStressTest.java';
  write(path.join(root, testPath), '@Tag("stress") class BetaStressTest {}\n');
  const result = validateStressSuitePolicy(policy([]), discoverStressTests(root));
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /missing from policy/);
});

withTempRepo((root) => {
  const result = validateStressSuitePolicy(
    policy([entry('modules/gamma', ['modules/gamma/src/test/java/example/GammaStressTest.java'])]),
    discoverStressTests(root),
  );
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /has no discovered/);
});

withTempRepo((root) => {
  write(path.join(root, 'modules/delta/src/test/java/example/DeltaTest.java'), [
    'class DeltaTest {',
    '  // @Tag("stress")',
    '  String text = "@Tag(\\"stress\\")";',
    '}',
    '',
  ].join('\n'));
  write(path.join(root, 'modules/delta/src/test/java/example/DeltaEvidenceTest.java'), [
    'import org.junit.jupiter.api.Tag;',
    '@Tag("evidence")',
    'class DeltaEvidenceTest {}',
    '',
  ].join('\n'));
  const discovered = discoverStressTests(root);
  assert.equal(discovered.size, 0);
});

console.log('test-verify-stress-suite-policy: PASS');
