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

function scaffold({ files = {}, forbidden = FORBIDDEN, surfaces = [], scan = {}, unrelatedStores = [] }) {
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
      { version: 1, surfaces, scan, forbiddenReintroduction: forbidden, unrelatedStores },
      null,
      2,
    ),
  );
  for (const [rel, content] of Object.entries(files)) write(rel, content);
  return root;
}

// A durable store fixture: a *Store.java whose own constructor takes a Path (persists to disk).
const durableStoreSrc = (cls) =>
  `package io.justsearch.x;\nimport java.nio.file.Path;\n` +
  `public final class ${cls} {\n  public ${cls}(Path dir) {}\n}\n`;

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
  const root = scaffold({
    files: {
      // The legitimate projection — does NOT match the fork pattern.
      'modules/ui/src/main/java/io/justsearch/ui/api/InteractionThreadController.java':
        'package io.justsearch.ui.api;\npublic final class InteractionThreadController {}\n',
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

await run('§18 C-1: an unclassified durable store -> fail; declared OR allowlisted -> pass', async () => {
  const forkPath = 'modules/app-x/src/main/java/io/justsearch/x/ConversationTimelineStore.java';

  // A NEW durable store (Path ctor), no canonical import, non-denylist name: unclassified -> FAIL.
  const unclassified = scaffold({
    forbidden: [],
    files: { [forkPath]: durableStoreSrc('ConversationTimelineStore') },
  });
  const r = await enforce(unclassified);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(
    ruleIds(r).includes('operation-surface/unclassified-durable-store'),
    `(${ruleIds(r).join(',')})`,
  );
  assert.ok(
    r.findings.some((f) => f.message.includes('ConversationTimelineStore.java')),
    'the unclassified store is named',
  );

  // The SAME store, declared as a surface -> no unclassified finding.
  const declared = scaffold({
    forbidden: [],
    surfaces: [
      { id: 'ct-store', kind: 'store', lang: 'java', path: forkPath, guard: 'self', consumesProjection: 'self' },
    ],
    files: { [forkPath]: durableStoreSrc('ConversationTimelineStore') },
  });
  assert.ok(
    !ruleIds(await enforce(declared)).includes('operation-surface/unclassified-durable-store'),
    'a declared durable store passes',
  );

  // The SAME store, on the unrelatedStores allowlist -> no unclassified finding.
  const allowlisted = scaffold({
    forbidden: [],
    unrelatedStores: [forkPath],
    files: { [forkPath]: durableStoreSrc('ConversationTimelineStore') },
  });
  assert.ok(
    !ruleIds(await enforce(allowlisted)).includes('operation-surface/unclassified-durable-store'),
    'an allowlisted unrelated store passes',
  );
});

await run('§18 C-1: an IN-MEMORY store (no Path ctor) is NOT required to be classified', async () => {
  // OperationHistoryStore-shaped: a *Store with no Path constructor must not trip the positive gate.
  const inMemory =
    'package io.justsearch.x;\npublic final class RingBufferStore {\n' +
    '  public RingBufferStore() {}\n  public RingBufferStore(int capacity) {}\n}\n';
  const root = scaffold({
    forbidden: [],
    files: { 'modules/app-x/src/main/java/io/justsearch/x/RingBufferStore.java': inMemory },
  });
  assert.ok(
    !ruleIds(await enforce(root)).includes('operation-surface/unclassified-durable-store'),
    'an in-memory store (no Path ctor) is excluded from the durable-store gate',
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
