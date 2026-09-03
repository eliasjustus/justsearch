import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { enforceTodoFixme } from './enforcer.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../../../..');
const registry = JSON.parse(fs.readFileSync(path.join(repoRoot, 'governance/registry.v1.json'), 'utf8'));
const gate = registry.gates.find((entry) => entry.id === 'todo-fixme');
assert.ok(gate, 'todo-fixme must remain registered');

const cases = [
  ['Java test sources', 'modules/sample/src/test/java/x/A.java', '// TODO'],
  ['TypeScript UI sources', 'modules/ui-web/src/x.ts', '// FIXME'],
  ['JavaScript scripts', 'scripts/x.mjs', '// XXX'],
  ['Python scripts', 'scripts/x.py', '# TODO'],
  ['PowerShell scripts', 'scripts/x.ps1', '# FIXME'],
  ['Rust shell sources', 'modules/shell/src-tauri/src/lib.rs', '// XXX'],
];

for (const [label, sourcePath, content] of cases) {
  const fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'todo-fixme-scope-'));
  try {
    const absoluteSource = path.join(fixtureRoot, sourcePath);
    fs.mkdirSync(path.dirname(absoluteSource), { recursive: true });
    fs.writeFileSync(absoluteSource, `${content}\n`, 'utf8');
    const baseline = path.join(fixtureRoot, gate.baseline.path);
    fs.mkdirSync(path.dirname(baseline), { recursive: true });
    fs.writeFileSync(baseline, '# empty fixture baseline\n', 'utf8');

    const result = await enforceTodoFixme({
      repoRoot: fixtureRoot,
      gate,
      baselineRef: null,
      fixtureMode: true,
      fixtureRoot,
    });
    assert.equal(result.verdict, 'fail', `${label} must be collected by the registered globs`);
    assert.ok(
      result.findings.some((finding) => finding.ruleId === 'todo-fixme/silent-growth'
        && finding.uri === sourcePath),
      `${label} must report its source path`,
    );
  } finally {
    fs.rmSync(fixtureRoot, { recursive: true, force: true });
  }
}

console.log(`todo-fixme enforcer scope: all ${cases.length} checks passed`);
