/**
 * Tests for the PMD ruleset-sync check (scripts/ci/check-pmd-ruleset-sync.mjs).
 *
 * A gate that cannot fail reads as coverage, so these exercise the BITE against synthetic
 * trees: a rule added to the authority and forgotten downstream, a property that drifted, an
 * undeclared extra rule, a subtraction with no stated reason, and the un-resolvable file-path
 * `ref` that makes PMD analyse zero files while Gradle still reports success. The last block
 * runs the real check against the real `config/pmd/` tree.
 *
 * Run: `node --test scripts/ci/check-pmd-ruleset-sync.test.mjs`
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { checkAll, parseRuleset, DERIVED, REPO_ROOT } from './check-pmd-ruleset-sync.mjs';

const AUTHORITY_RULES = [
  '<rule ref="category/java/bestpractices.xml/SystemPrintln"/>',
  '<rule ref="category/java/multithreading.xml/NonThreadSafeSingleton"/>',
  '<rule ref="category/java/errorprone.xml/DoNotTerminateVM"/>',
  '<rule ref="category/java/errorprone.xml/EmptyCatchBlock">',
  '  <properties><property name="allowCommentedBlocks" value="true"/></properties>',
  '</rule>',
];

/** Minimal ruleset document. */
function ruleset(description, rules) {
  return `<?xml version="1.0" encoding="UTF-8"?>\n<ruleset>\n<description>\n${description}\n</description>\n${rules.join('\n')}\n</ruleset>\n`;
}

/** Build a fake repo root with the three ruleset paths the check reads. */
function withTree(files, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmd-sync-'));
  try {
    for (const [rel, body] of Object.entries(files)) {
      fs.mkdirSync(path.dirname(path.join(dir, rel)), { recursive: true });
      fs.writeFileSync(path.join(dir, rel), body, 'utf8');
    }
    return fn(dir);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

/** A tree where both derived files are correct; `mutate` may break one of them. */
function tree(mutate = (f) => f) {
  const cli = ruleset('drops SystemPrintln and DoNotTerminateVM: CLI entry points print and exit', [
    AUTHORITY_RULES[1],
    ...AUTHORITY_RULES.slice(3),
  ]);
  const tests = ruleset('drops SystemPrintln and NonThreadSafeSingleton: tests print; JUnit serialises fixtures', [
    AUTHORITY_RULES[2],
    ...AUTHORITY_RULES.slice(3),
  ]);
  return mutate({
    'config/pmd/ruleset.xml': ruleset('authority', AUTHORITY_RULES),
    'config/pmd/ruleset-cli-tools.xml': cli,
    'config/pmd/ruleset-tests.xml': tests,
  });
}

test('a correct tree passes', () => {
  withTree(tree(), (dir) => assert.deepEqual(checkAll(dir), []));
});

test('a rule present in the authority but missing downstream fails', () => {
  const files = tree((f) => {
    f['config/pmd/ruleset-tests.xml'] = ruleset('drops SystemPrintln and NonThreadSafeSingleton', [
      AUTHORITY_RULES[2],
    ]);
    return f;
  });
  withTree(files, (dir) => {
    const errors = checkAll(dir);
    assert.equal(errors.length, 1);
    assert.match(errors[0], /ruleset-tests\.xml: missing "EmptyCatchBlock"/);
  });
});

test('a drifted rule property fails', () => {
  const files = tree((f) => {
    f['config/pmd/ruleset-tests.xml'] = f['config/pmd/ruleset-tests.xml'].replace('value="true"', 'value="false"');
    return f;
  });
  withTree(files, (dir) => {
    const errors = checkAll(dir);
    assert.equal(errors.length, 1);
    assert.match(errors[0], /"EmptyCatchBlock" properties differ/);
  });
});

test('a rule absent from the authority cannot be smuggled into a derived ruleset', () => {
  const files = tree((f) => {
    f['config/pmd/ruleset-cli-tools.xml'] = f['config/pmd/ruleset-cli-tools.xml'].replace(
      '</ruleset>',
      '<rule ref="category/java/design.xml/GodClass"/>\n</ruleset>',
    );
    return f;
  });
  withTree(files, (dir) => {
    const errors = checkAll(dir);
    assert.equal(errors.length, 1);
    assert.match(errors[0], /carries "GodClass", absent from/);
  });
});

test('a declared subtraction that the description does not name fails', () => {
  const files = tree((f) => {
    f['config/pmd/ruleset-tests.xml'] = f['config/pmd/ruleset-tests.xml'].replace(
      'drops SystemPrintln and NonThreadSafeSingleton: tests print; JUnit serialises fixtures',
      'a ruleset',
    );
    return f;
  });
  withTree(files, (dir) => {
    const errors = checkAll(dir);
    assert.equal(errors.length, 2);
    for (const e of errors) assert.match(e, /without naming it in <description>/);
  });
});

test('a subtracted rule that is still carried fails', () => {
  const files = tree((f) => {
    f['config/pmd/ruleset-tests.xml'] = ruleset(
      'drops SystemPrintln and NonThreadSafeSingleton',
      AUTHORITY_RULES.slice(1),
    );
    return f;
  });
  withTree(files, (dir) => {
    const errors = checkAll(dir);
    assert.equal(errors.length, 1);
    assert.match(errors[0], /carries "NonThreadSafeSingleton", which it declares droppable/);
  });
});

test('a file-path ref — the one PMD silently resolves to zero files — fails', () => {
  const files = tree((f) => {
    f['config/pmd/ruleset-tests.xml'] = ruleset('drops SystemPrintln and NonThreadSafeSingleton', [
      '<rule ref="config/pmd/ruleset.xml"/>',
    ]);
    return f;
  });
  withTree(files, (dir) => {
    const errors = checkAll(dir);
    assert.ok(errors.some((e) => /is not a category\/java\/\.\.\. classpath reference/.test(e)));
  });
});

test('parseRuleset keys rules by simple name and normalises property order', () => {
  const a = parseRuleset(
    '<rule ref="category/java/errorprone.xml/EmptyCatchBlock"><properties><property name="b" value="2"/><property name="a" value="1"/></properties></rule>',
  );
  const b = parseRuleset(
    '<rule ref="category/java/errorprone.xml/EmptyCatchBlock"><properties><property name="a" value="1"/><property name="b" value="2"/></properties></rule>',
  );
  assert.deepEqual([...a.rules.keys()], ['EmptyCatchBlock']);
  assert.equal(a.rules.get('EmptyCatchBlock'), b.rules.get('EmptyCatchBlock'));
});

test('the real config/pmd tree is in sync', () => {
  assert.deepEqual(checkAll(REPO_ROOT), []);
  assert.deepEqual(Object.keys(DERIVED).sort(), [
    'config/pmd/ruleset-cli-tools.xml',
    'config/pmd/ruleset-tests.xml',
  ]);
});
