import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import {
  appendRunRecord,
  readGateHistory,
  readHistory,
  readRepositoryHealthHistory,
} from './history.mjs';

const repoRoot = mkdtempSync(join(tmpdir(), 'governance-history-'));
mkdirSync(join(repoRoot, 'modules', 'a', 'src', 'main', 'java'), { recursive: true });
writeFileSync(join(repoRoot, 'settings.gradle.kts'), 'include(":modules:a")\n');
writeFileSync(join(repoRoot, 'modules', 'a', 'src', 'main', 'java', 'A.java'), 'class A {}\n');
const path = 'tmp/test-history.ndjson';

appendRunRecord({
  repoRoot,
  path,
  runs: [{ categoryId: 'sample', findings: [{ level: 'warning' }] }],
  verdicts: [{ gate: 'sample', verdict: 'pass' }],
});
const full = join(repoRoot, path);
writeFileSync(
  full,
  `${JSON.stringify({ ts: 'legacy', gate: 'legacy-gate', verdict: 'pass', findings: {} })}\n`,
  { flag: 'a' },
);

assert.equal(readHistory({ repoRoot, path }).length, 3);
const gates = readGateHistory({ repoRoot, path });
assert.deepEqual(gates.map(row => row.gate), ['sample', 'legacy-gate']);
assert.equal(gates[0].schemaVersion, 2);
const health = readRepositoryHealthHistory({ repoRoot, path });
assert.equal(health.length, 1);
assert.equal(health[0].metrics.gradleModuleCount, 1);

console.log('history.test: OK');
