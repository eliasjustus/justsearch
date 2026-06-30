#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { discoverEvidenceSubjects, validateTestEvidencePolicy } from './verify-test-evidence-policy.mjs';

function write(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

function withTempRepo(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-test-evidence-policy-'));
  try {
    fs.writeFileSync(path.join(root, 'settings.gradle.kts'), 'rootProject.name = "test"\n', 'utf8');
    return fn(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function basePolicy(overrides = {}) {
  return {
    kind: 'justsearch-test-evidence-policy.v1',
    version: 1,
    ciSkippedSites: [],
    declaredTags: [],
    ...overrides,
  };
}

function skipEntry(pathName, element) {
  return {
    path: pathName,
    element,
    tier: 'local-test',
    owner: 'test owner',
    reason: 'test reason',
    replacementEvidence: 'test replacement',
    cadence: 'test cadence',
  };
}

function tagEntry(tag) {
  return {
    tag,
    tier: 'tag-tier',
    owner: 'tag owner',
    reason: 'tag reason',
    cadence: 'tag cadence',
  };
}

withTempRepo((root) => {
  const file = 'modules/alpha/src/test/java/example/AlphaTest.java';
  write(path.join(root, file), [
    'package example;',
    'import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;',
    '@DisabledIfEnvironmentVariable(named = "CI", matches = "true")',
    'class AlphaTest {',
    '  @DisabledIfEnvironmentVariable(named = "CI", matches = "true")',
    '  void methodSkip() {}',
    '  @DisabledIfEnvironmentVariable(named = "CI", matches = "true")',
    '  class NestedSkip {}',
    '}',
    '',
  ].join('\n'));
  const discovered = discoverEvidenceSubjects(root);
  assert.deepEqual(discovered.ciSkippedSites.map((site) => site.element), [
    'class AlphaTest',
    'method methodSkip',
    'class NestedSkip',
  ]);
  const result = validateTestEvidencePolicy(basePolicy({
    ciSkippedSites: [
      skipEntry(file, 'class AlphaTest'),
      skipEntry(file, 'method methodSkip'),
      skipEntry(file, 'class NestedSkip'),
    ],
  }), discovered);
  assert.equal(result.ok, true, result.issues.join('\n'));
});

withTempRepo((root) => {
  const file = 'modules/beta/src/test/java/example/BetaTest.java';
  write(path.join(root, file), [
    'import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;',
    '@DisabledIfEnvironmentVariable(named = "CI", matches = "true")',
    'class BetaTest {}',
    '',
  ].join('\n'));
  const result = validateTestEvidencePolicy(basePolicy(), discoverEvidenceSubjects(root));
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /CI-skipped test site missing/);
});

withTempRepo((root) => {
  const result = validateTestEvidencePolicy(basePolicy({
    ciSkippedSites: [skipEntry('modules/gamma/src/test/java/example/GammaTest.java', 'class GammaTest')],
  }), discoverEvidenceSubjects(root));
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /has no discovered CI skip/);
});

withTempRepo((root) => {
  const file = 'modules/delta/src/test/java/example/DeltaTest.java';
  write(path.join(root, file), [
    'import org.junit.jupiter.api.Tag;',
    '/** @Tag("stress") in comments must be ignored. */',
    'class DeltaTest {',
    '  String literal = "@Tag(\\"evidence\\")";',
    '}',
    '@Tag("stress")',
    'class DeltaStressTest {}',
    '@Tag("evidence")',
    'class DeltaEvidenceTest {}',
    '',
  ].join('\n'));
  const discovered = discoverEvidenceSubjects(root);
  assert.deepEqual(discovered.tags.map((tag) => tag.tag), ['evidence', 'stress']);
  const result = validateTestEvidencePolicy(basePolicy({
    declaredTags: [tagEntry('evidence')],
  }), discovered);
  assert.equal(result.ok, true, result.issues.join('\n'));
});

withTempRepo((root) => {
  write(path.join(root, 'modules/epsilon/src/test/java/example/EpsilonTest.java'), [
    'import org.junit.jupiter.api.Tag;',
    '@Tag("experiment")',
    'class EpsilonTest {}',
    '',
  ].join('\n'));
  const missing = validateTestEvidencePolicy(basePolicy(), discoverEvidenceSubjects(root));
  assert.equal(missing.ok, false);
  assert.match(missing.issues.join('\n'), /JUnit tag missing from policy: experiment/);

  const stale = validateTestEvidencePolicy(basePolicy({
    declaredTags: [tagEntry('experiment'), tagEntry('evidence')],
  }), discoverEvidenceSubjects(root));
  assert.equal(stale.ok, false);
  assert.match(stale.issues.join('\n'), /has no discovered tag: evidence/);
});

withTempRepo((root) => {
  write(path.join(root, 'modules/zeta/src/test/java/example/ZetaStressTest.java'), [
    'import org.junit.jupiter.api.Tag;',
    '@Tag("stress")',
    'class ZetaStressTest {}',
    '',
  ].join('\n'));
  const result = validateTestEvidencePolicy(basePolicy({
    declaredTags: [tagEntry('stress')],
  }), discoverEvidenceSubjects(root));
  assert.equal(result.ok, false);
  assert.match(result.issues.join('\n'), /must not register stress/);
});

console.log('test-verify-test-evidence-policy: PASS');
