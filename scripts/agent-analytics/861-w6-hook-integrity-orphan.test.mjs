/**
 * Tempdoc 861 Phase 6 — the hook-integrity gate's file->manifest direction.
 *
 * Every rule the gate already had (wiring, live-wiring, cwd-invariant, load, bite,
 * live-wiring) starts FROM the manifest and checks outward. None of them ever
 * lists the hook directory, so a hook FILE that never made it into the manifest was
 * invisible to all five — which is exactly how `ui-shot-cleanup.mjs` sat on disk,
 * described as a live SessionEnd hook by an always-loaded rules file, for the
 * project's whole public history while never once firing (861 SS3c).
 *
 * This proves BOTH directions on a fixture that reproduces the pre-sweep tree shape
 * (one orphaned file beside the catalogued ones, plus a `*.test.mjs` sibling that must
 * stay excluded) and on a clean tree where the disk and the catalog agree.
 *
 * Sited under scripts/agent-analytics/ (not the gate's own scripts/governance/gates/
 * test dir) so run-all-tests.mjs auto-discovery puts it in CI — 861 SS7.6 found that
 * the gate's own enforcer.test.mjs/truth-table.test.mjs run in CI nowhere, which is a
 * close cousin of the exact defect this phase closes.
 *
 * Run: `node scripts/agent-analytics/861-w6-hook-integrity-orphan.test.mjs`
 * (exits non-zero on failure; auto-discovered by run-all-tests.mjs)
 */
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { enforceHookIntegrity } from '../governance/gates/hook-integrity/enforcer.mjs';

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

function writeFixture(root, { includeOrphan }) {
  const w = (rel, content) => {
    const p = join(root, rel);
    mkdirSync(dirname(p), { recursive: true });
    writeFileSync(p, content, 'utf8');
  };

  w('governance/agent-hooks.v1.json', JSON.stringify({
    kind: 'agent-hooks-manifest.v1',
    version: 1,
    hookDir: 'scripts/agent-analytics/hooks',
    hooks: {
      good: { file: 'good.mjs', role: 'advisory' },
    },
    bindings: {
      PreToolUse: [{ matcher: 'Bash', hooks: [{ hookId: 'good', timeout: 5 }] }],
    },
  }));
  // cwd-invariant exec-form settings, live-wiring-satisfied, so only the orphan check
  // can move the verdict.
  w('.claude/settings.local.json', JSON.stringify({
    hooks: {
      PreToolUse: [
        { matcher: 'Bash', hooks: [
          { type: 'command', command: 'node', args: ['${CLAUDE_PROJECT_DIR}/scripts/agent-analytics/hooks/good.mjs'], timeout: 5 },
        ] },
      ],
    },
  }));
  w('scripts/agent-analytics/hooks/good.mjs', 'process.exit(0);\n');
  // A test-file sibling must stay excluded regardless of catalog membership.
  w('scripts/agent-analytics/hooks/good.test.mjs', 'process.exit(0);\n');
  if (includeOrphan) {
    // The pre-sweep tree shape: a hook file with no manifest row — exactly
    // ui-shot-cleanup.mjs's situation before Phase 7 deletes it.
    w('scripts/agent-analytics/hooks/orphan.mjs', 'process.exit(0);\n');
  }
}

// --- Direction 1: the orphan IS caught on the pre-sweep tree shape (fixture). ---
{
  const root = mkdtempSync(join(tmpdir(), 'hook-integrity-orphan-'));
  try {
    writeFixture(root, { includeOrphan: true });
    const gate = { config: { manifest: 'governance/agent-hooks.v1.json' }, baseline: {} };
    const res = await enforceHookIntegrity({ repoRoot: root, gate, fixtureMode: true, fixtureRoot: root });

    const orphanFails = res.findings.filter((f) => f.ruleId === 'hook-integrity/orphan-hook-file');
    ok('orphan.mjs is flagged as an orphan hook file', orphanFails.some((f) => f.message.includes('orphan.mjs')));
    ok('exactly one orphan is flagged (the pre-sweep shape: one orphan)', orphanFails.length === 1);
    ok('the catalogued good.mjs is NOT flagged as an orphan', !orphanFails.some((f) => f.message.includes('good.mjs')));
    ok('the *.test.mjs sibling is NOT flagged as an orphan', !orphanFails.some((f) => f.message.includes('good.test.mjs')));
    ok('overall verdict is fail when an orphan hook file exists', res.verdict === 'fail');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

// --- Direction 2: a clean tree (disk and catalog agree) is green. ---
{
  const root = mkdtempSync(join(tmpdir(), 'hook-integrity-clean-'));
  try {
    writeFixture(root, { includeOrphan: false });
    const gate = { config: { manifest: 'governance/agent-hooks.v1.json' }, baseline: {} };
    const res = await enforceHookIntegrity({ repoRoot: root, gate, fixtureMode: true, fixtureRoot: root });

    const orphanFindings = res.findings.filter((f) => f.ruleId === 'hook-integrity/orphan-hook-file');
    ok('a clean tree raises zero orphan-hook-file findings', orphanFindings.length === 0);
    ok('a clean tree is an overall pass', res.verdict === 'pass');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

if (failures.length > 0) {
  console.error(`861-w6-hook-integrity-orphan.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`861-w6-hook-integrity-orphan.test: all ${passed} checks passed`);
