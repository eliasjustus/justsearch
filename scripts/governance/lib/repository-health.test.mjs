import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { collectRepositoryHealth } from './repository-health.mjs';

const root = mkdtempSync(join(tmpdir(), 'repository-health-'));
const write = (rel, text) => {
  const full = join(root, rel);
  mkdirSync(join(full, '..'), { recursive: true });
  writeFileSync(full, text);
};

write('settings.gradle.kts', 'include(":modules:a", ":modules:b", ":modules:a")\n');
write('modules/a/src/main/java/A.java', 'class A {\n}\n');
write('modules/a/src/test/java/ATest.java', 'class ATest {}\n');
write('modules/a/src/testFixtures/java/AFixture.java', 'class AFixture {}\n');
write('modules/b/src/integrationTest/java/BIT.java', 'class BIT {}\n');
write('modules/ui-web/src/View.ts', 'export const view = 1;\n');
write('modules/ui-web/src/View.test.ts', 'test("view", () => {});\n');
write('modules/ui-web/src/generated/ignored.ts', 'one\ntwo\n');

const result = collectRepositoryHealth(root);
assert.equal(result.gradleModuleCount, 2);
assert.equal(result.testFileCount, 4);
assert.equal(result.productionSourceLocByModule.a, 2);
assert.equal(result.productionSourceLocByModule['ui-web'], 1);
assert.equal(result.productionSourceLocByModule.b, undefined);
assert.match(result.units.productionSourceLoc, /physical lines/);

console.log('repository-health.test: OK');
