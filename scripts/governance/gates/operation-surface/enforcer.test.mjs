/**
 * Tempdoc 561 P-C — integration tests for the operation-surface enforcer's forbidden-reintroduction
 * guard (the §11 fork-class structural backstop). The canonical-type import-scan cannot see a
 * new-vocabulary write-store, so a named pattern catches the exact second-authority fork §11 removed
 * (the InteractionLog thread store). This proves the guard FIRES on reintroduction and stays quiet
 * when clean (the wrong-gate lesson: prove the gate fires in the target scenario, not just exists).
 *
 * Run with: `node scripts/governance/gates/operation-surface/enforcer.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { enforceOperationSurface } from './enforcer.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

const GATE = { config: { register: 'governance/operation-surfaces.v1.json' } };

const FORBIDDEN = [
  {
    pattern: '(InteractionLog|InteractionStore|ThreadStore|ConversationLog)\\.(java|ts|tsx)$',
    canonical: 'project ConversationStore + AgentRunStore; tempdoc 561 §11',
    allow: [],
  },
];

function scaffold({
  files = {},
  forbidden = FORBIDDEN,
  surfaces = [],
  scan = {},
}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'opsurface-'));
  tmpDirs.push(root);
  const write = (rel, content) => {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  };
  write(
    'governance/operation-surfaces.v1.json',
    JSON.stringify(
      { version: 1, surfaces, scan, forbiddenReintroduction: forbidden },
      null,
      2,
    ),
  );
  for (const [rel, content] of Object.entries(files)) write(rel, content);
  return root;
}

async function enforce(fixtureRoot) {
  return enforceOperationSurface({ repoRoot: fixtureRoot, gate: GATE, fixtureMode: true, fixtureRoot });
}

async function run(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const ruleIds = (r) => r.findings.map((f) => f.ruleId);

await run('reintroduced InteractionLog store → fail (forbidden-second-authority)', async () => {
  const root = scaffold({
    files: {
      'modules/app-services/src/main/java/io/justsearch/app/services/conversation/InteractionLog.java':
        'package io.justsearch.app.services.conversation;\npublic final class InteractionLog {}\n',
    },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(
    ruleIds(r).includes('operation-surface/forbidden-second-authority'),
    `(${ruleIds(r).join(',')})`,
  );
});

await run('FileInteractionLog / ThreadStore siblings also caught', async () => {
  const root = scaffold({
    files: {
      'modules/app-agent/src/main/java/io/justsearch/agent/FileInteractionLog.java':
        'package io.justsearch.agent;\npublic final class FileInteractionLog {}\n',
      'modules/ui-web/src/shell-v0/state/ThreadStore.ts': 'export class ThreadStore {}\n',
    },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('operation-surface/forbidden-second-authority'));
});

await run('no forbidden file present → pass (guard quiet when clean)', async () => {
  const projectionPath =
    'modules/ui/src/main/java/io/justsearch/ui/api/InteractionThreadController.java';
  const root = scaffold({
    scan: {
      javaImportPatterns: ['io.justsearch.agent.AgentRunStore'],
      javaMainRoots: ['modules'],
      javaInclude: '/src/main/java/',
      expectedMinPopulation: 1,
    },
    surfaces: [
      {
        id: 'interaction-thread',
        kind: 'projection',
        lang: 'java',
        path: projectionPath,
        guard: 'self',
        consumesProjection: 'self',
      },
    ],
    files: {
      // The legitimate projection — does NOT match the fork pattern.
      [projectionPath]:
        'package io.justsearch.ui.api;\nimport io.justsearch.agent.AgentRunStore;\n' +
        'public final class InteractionThreadController {}\n',
    },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(!ruleIds(r).includes('operation-surface/forbidden-second-authority'));
});

await run('a SECOND memory store → fail; the canonical FileMemoryStore is allowed (P-E)', async () => {
  const MEMORY_FORBIDDEN = [
    {
      pattern: 'Memory(Store|Log|Bank)\\.(java|ts|tsx)$',
      canonical: 'one authority — FileMemoryStore; tempdoc 561 P-E',
      allow: ['modules/app-agent/src/main/java/io/justsearch/agent/FileMemoryStore.java'],
    },
  ];
  const root = scaffold({
    forbidden: MEMORY_FORBIDDEN,
    files: {
      // The canonical authority — allow-listed, must NOT trip.
      'modules/app-agent/src/main/java/io/justsearch/agent/FileMemoryStore.java':
        'package io.justsearch.agent;\npublic final class FileMemoryStore {}\n',
      // A reintroduced SECOND memory authority — MUST trip.
      'modules/app-agent/src/main/java/io/justsearch/agent/SqliteMemoryStore.java':
        'package io.justsearch.agent;\npublic final class SqliteMemoryStore {}\n',
    },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('operation-surface/forbidden-second-authority'));
  // The finding names the fork, not the canonical.
  assert.ok(
    r.findings.some((f) => f.message.includes('SqliteMemoryStore')),
    'the second store is named',
  );
  // FileMemoryStore may appear in the fix-hint ("project FileMemoryStore"), but must never be the
  // flagged SUBJECT ("<path>FileMemoryStore.java matches forbidden ...").
  assert.ok(
    !r.findings.some((f) => f.message.includes('FileMemoryStore.java matches forbidden')),
    'the canonical authority is allow-listed, not the flagged subject',
  );
});

await run('an undeclared importer of a canonical interaction type -> fail; declared -> pass (P-C auto-coverage)', async () => {
  // Tempdoc 561 P-C Item 2: the real auto-coverage — a NEW referencer of the canonical agent record
  // type fails the build unless declared, replacing reliance on the name-blocklist backstop.
  const SCAN = {
    javaImportPatterns: ['io.justsearch.agent.AgentRunStore'],
    javaMainRoots: ['modules'],
    javaInclude: '/src/main/java/',
  };
  const importer = 'modules/app-x/src/main/java/io/justsearch/x/NewAgentView.java';
  const src =
    'package io.justsearch.x;\nimport io.justsearch.agent.AgentRunStore;\n'
    + 'public final class NewAgentView { AgentRunStore s; }\n';

  // Undeclared referencer of the canonical type -> fail.
  const undeclared = scaffold({ scan: SCAN, forbidden: [], files: { [importer]: src } });
  const r = await enforce(undeclared);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(
    ruleIds(r).includes('operation-surface/undeclared-surface'),
    `(${ruleIds(r).join(',')})`,
  );

  // The SAME importer, declared -> no undeclared-surface finding.
  const declared = scaffold({
    scan: SCAN,
    forbidden: [],
    surfaces: [
      { id: 'new-agent-view', kind: 'projection', lang: 'java', path: importer, guard: 'self', consumesProjection: 'self' },
    ],
    files: { [importer]: src },
  });
  const r2 = await enforce(declared);
  assert.ok(
    !ruleIds(r2).includes('operation-surface/undeclared-surface'),
    `a declared referencer must pass (${ruleIds(r2).join(',')})`,
  );
});

await run('a test file for the fork does NOT trip the guard (basename-anchored)', async () => {
  const root = scaffold({
    files: {
      'modules/app-agent/src/test/java/io/justsearch/agent/InteractionLogTest.java':
        'package io.justsearch.agent;\nclass InteractionLogTest {}\n',
    },
  });
  const r = await enforce(root);
  assert.ok(
    !ruleIds(r).includes('operation-surface/forbidden-second-authority'),
    `a *Test file should not match the store pattern (${ruleIds(r).join(',')})`,
  );
});

for (const d of tmpDirs) {
  try {
    fs.rmSync(d, { recursive: true, force: true });
  } catch {
    /* best-effort cleanup */
  }
}

if (failures.length > 0) {
  console.error(`operation-surface enforcer: ${failures.length} FAILED`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`operation-surface enforcer: ${passed} passed`);
