/**
 * Enforcer-level regression for the load probe (tempdoc 592 finding #1).
 *
 * Proves the behavioral delta vs the prior `node --check` (syntax-only) probe: a hook whose
 * SYNTAX is valid but whose IMPORT graph is broken (missing module) must be flagged as a
 * load failure. `node --check` would have passed it; the benign-spawn probe (real spawn with
 * `{}` stdin + JUSTSEARCH_DISABLE_HOOKS=1) resolves imports and catches it.
 *
 * Run: `node scripts/governance/gates/hook-integrity/enforcer.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { enforceHookIntegrity } from './enforcer.mjs';

const root = mkdtempSync(join(tmpdir(), 'hook-integrity-enf-'));
const w = (rel, content) => {
  const p = join(root, rel);
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, content, 'utf8');
};

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};

try {
  // Two advisory hooks: one valid, one with valid syntax but a broken import.
  w('governance/agent-hooks.v1.json', JSON.stringify({
    kind: 'agent-hooks-manifest.v1',
    version: 1,
    hookDir: 'scripts/agent-analytics/hooks',
    hooks: {
      good: { file: 'good.mjs', role: 'advisory' },
      broken: { file: 'broken.mjs', role: 'advisory' },
    },
    bindings: {
      PreToolUse: [
        { matcher: 'Bash', hooks: [{ hookId: 'good', timeout: 5 }, { hookId: 'broken', timeout: 5 }] },
      ],
    },
  }));
  // cwd-invariant exec-form settings so only the LOAD verdict can fail here.
  w('.claude/settings.local.json', JSON.stringify({
    hooks: {
      PreToolUse: [
        { matcher: 'Bash', hooks: [
          { type: 'command', command: 'node', args: ['${CLAUDE_PROJECT_DIR}/scripts/agent-analytics/hooks/good.mjs'], timeout: 5 },
          { type: 'command', command: 'node', args: ['${CLAUDE_PROJECT_DIR}/scripts/agent-analytics/hooks/broken.mjs'], timeout: 5 },
        ] },
      ],
    },
  }));
  w('scripts/agent-analytics/hooks/good.mjs', 'process.exit(0);\n');
  // Valid syntax, unresolved import → crashes at module load (ERR_MODULE_NOT_FOUND).
  w('scripts/agent-analytics/hooks/broken.mjs', "import './nonexistent-module.mjs';\nprocess.exit(0);\n");

  const gate = { config: { manifest: 'governance/agent-hooks.v1.json' }, baseline: {} };
  const res = await enforceHookIntegrity({ repoRoot: root, gate, fixtureMode: true, fixtureRoot: root });

  const loadFails = res.findings.filter((f) => f.ruleId === 'hook-integrity/hook-load-failure');
  ok('broken-import hook is flagged as a load failure', loadFails.some((f) => f.message.includes("'broken'")));
  ok('valid hook is NOT flagged as a load failure', !loadFails.some((f) => f.message.includes("'good'")));
  ok('overall verdict is fail when a hook crashes on load', res.verdict === 'fail');
} finally {
  rmSync(root, { recursive: true, force: true });
}

if (failures.length > 0) {
  console.error(`hook-integrity enforcer.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`hook-integrity enforcer.test: all ${passed} checks passed`);
