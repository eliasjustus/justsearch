/**
 * Unit tests for skill-delivery.mjs (tempdoc 928). No real transcripts or
 * prompts are read; fixtures are created under the OS temp directory.
 */

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  buildSkillDeliveryReport,
  classifySkillAttempt,
  findSkillTargets,
  inventorySkills,
  isSkillReadExchange,
  parseArgs,
  parseSkillFrontmatter,
} from './skill-delivery.mjs';

const CLI = fileURLToPath(new URL('./skill-delivery.mjs', import.meta.url));

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (error) {
    failures.push(`${label}: ${error.message}`);
  }
}

function makeRepo() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-delivery-test-'));
  const claudeDir = path.join(root, '.claude', 'skills', 'alpha');
  const codexDir = path.join(root, '.agents', 'skills', 'alpha');
  fs.mkdirSync(path.join(codexDir, 'agents'), { recursive: true });
  fs.mkdirSync(claudeDir, { recursive: true });
  const claudeText = [
    '---',
    'description: "Alpha delivery fixture."',
    'disable-model-invocation: true',
    '---',
    '',
    '# Alpha',
    '',
    'REQUIRED_CURRENT_SENTINEL',
    '',
  ].join('\n');
  const codexText = [
    '---',
    'name: alpha',
    'description: >-',
    '  Codex alpha',
    '  delivery fixture.',
    '---',
    'CODEX_CURRENT_SENTINEL',
    '',
  ].join('\n');
  fs.writeFileSync(path.join(claudeDir, 'SKILL.md'), claudeText, 'utf8');
  fs.writeFileSync(path.join(codexDir, 'SKILL.md'), codexText, 'utf8');
  fs.writeFileSync(path.join(codexDir, 'agents', 'openai.yaml'), 'policy:\n  allow_implicit_invocation: false\n', 'utf8');
  const tracking = {
    known: true,
    headComparisonKnown: true,
    changedPaths: new Set(),
    paths: new Set([
      '.agents/skills/alpha/SKILL.md',
      '.agents/skills/alpha/agents/openai.yaml',
      '.claude/skills/alpha/SKILL.md',
    ]),
  };
  return { root, claudeText, codexText, tracking };
}

function exchange(overrides = {}) {
  return {
    sessionId: overrides.sessionId ?? 's1',
    input: overrides.input ?? 'Get-Content .claude/skills/alpha/SKILL.md',
    outputText: overrides.outputText ?? '',
    missingOutput: overrides.missingOutput ?? false,
    outputTimestampUnknown: overrides.outputTimestampUnknown ?? false,
  };
}

run('parseSkillFrontmatter separates scalar metadata from the body', () => {
  const parsed = parseSkillFrontmatter('---\r\ndescription: "Example"\r\nuser-invocable: true\r\n---\r\nBody\r\n');
  assert.equal(parsed.frontmatter.description, 'Example');
  assert.equal(parsed.frontmatter['user-invocable'], true);
  assert.equal(parsed.body, 'Body\n');
});

run('parseSkillFrontmatter folds the block-scalar descriptions used by Codex skills', () => {
  const parsed = parseSkillFrontmatter('---\ndescription: >-\n  First line\n  continues here.\n\n  Second paragraph.\n---\nBody\n');
  assert.equal(parsed.frontmatter.description, 'First line continues here.\nSecond paragraph.');
  assert.equal(parsed.body, 'Body\n');
});

run('inventory keeps unequal Codex and Claude authorities independent', () => {
  const fixture = makeRepo();
  const inventory = inventorySkills(fixture.root, fixture.tracking);
  assert.equal(inventory.length, 2);
  const claude = inventory.find((skill) => skill.harness === 'claude-code');
  const codex = inventory.find((skill) => skill.harness === 'codex-cli');
  assert.equal(claude.description, 'Alpha delivery fixture.');
  assert.equal(codex.description, 'Codex alpha delivery fixture.');
  assert.equal(claude.explicitOnly, true);
  assert.equal(codex.explicitOnly, true);
  assert.equal(claude.text, fixture.claudeText);
  assert.equal(codex.text, fixture.codexText);
  assert.equal(claude.tracked, true);
  assert.equal(codex.policyTracked, true);
  assert.equal(codex.currentMatchesHead, true);
  assert.equal(codex.metadataValid, true);
  assert.equal(codex.declaredName, 'alpha');
  assert.notEqual(claude.totalChars, codex.totalChars);
});

run('inventory diagnoses Codex name mismatches instead of silently trusting the directory', () => {
  const fixture = makeRepo();
  fs.writeFileSync(
    path.join(fixture.root, '.agents', 'skills', 'alpha', 'SKILL.md'),
    '---\nname: beta\ndescription: Valid description.\n---\nBody\n',
    'utf8',
  );
  const row = inventorySkills(fixture.root, fixture.tracking)
    .find((skill) => skill.harness === 'codex-cli');
  assert.equal(row.name, 'alpha');
  assert.equal(row.declaredName, 'beta');
  assert.equal(row.metadataValid, false);
  assert.match(row.metadataErrors.join(' '), /does not match directory/);
});

run('a Codex skill without an optional policy file can still match HEAD', () => {
  const fixture = makeRepo();
  const policyFile = path.join(fixture.root, '.agents', 'skills', 'alpha', 'agents', 'openai.yaml');
  fs.unlinkSync(policyFile);
  fixture.tracking.paths.delete('.agents/skills/alpha/agents/openai.yaml');
  const row = inventorySkills(fixture.root, fixture.tracking)
    .find((skill) => skill.harness === 'codex-cli');
  assert.equal(row.policyPath, null);
  assert.equal(row.policyTracked, false);
  assert.equal(row.currentMatchesHead, true);
});

run('findSkillTargets handles both native surfaces and escaped Windows separators', () => {
  const targets = findSkillTargets('Get-Content F:\\\\repo\\\\.agents\\\\skills\\\\alpha\\\\SKILL.md; cat .claude/skills/beta/SKILL.md');
  assert.deepEqual(targets, [
    { kind: 'agents', harness: 'codex-cli', name: 'alpha' },
    { kind: 'claude', harness: 'claude-code', name: 'beta' },
  ]);
});

run('inventory separates locally present untracked skills from checked-in rows', () => {
  const fixture = makeRepo();
  const localOnly = path.join(fixture.root, '.agents', 'skills', 'local-only');
  fs.mkdirSync(localOnly, { recursive: true });
  fs.writeFileSync(path.join(localOnly, 'SKILL.md'), '---\nname: local-only\ndescription: Local only.\n---\nBody\n', 'utf8');
  const inventory = inventorySkills(fixture.root, fixture.tracking);
  const row = inventory.find((skill) => skill.name === 'local-only');
  assert.equal(row.harness, 'codex-cli');
  assert.equal(row.tracked, false);
});

run('read-attempt filter rejects paths embedded only in an apply_patch payload', () => {
  assert.equal(isSkillReadExchange({
    name: 'exec',
    input: 'const patch = "test says Get-Content .claude/skills/alpha/SKILL.md"; await tools.apply_patch(patch);',
  }), false);
  assert.equal(isSkillReadExchange({
    name: 'exec',
    input: 'await tools.exec_command({cmd:"Get-Content .claude/skills/alpha/SKILL.md"});',
  }), true);
});

run('exact current content proves full delivery across newline conventions', () => {
  const expectedText = '# Alpha\nrequired\n';
  assert.equal(classifySkillAttempt({
    input: 'Get-Content .claude/skills/alpha/SKILL.md',
    outputText: `header\r\n${expectedText.replaceAll('\n', '\r\n')}footer`,
    missingOutput: false,
    expectedText,
  }), 'proven_full_current');
});

run('explicit tool truncation is distinct from a claim that the skill failed', () => {
  assert.equal(classifySkillAttempt({
    input: 'Get-Content .agents/skills/alpha/SKILL.md; git diff',
    outputText: 'Warning: truncated output (original token count: 50028)\npartial bytes',
    missingOutput: false,
    expectedText: 'current bytes absent',
  }), 'tool_output_truncated');
});

run('an output without event time cannot prove an as-of classification', () => {
  assert.equal(classifySkillAttempt({
    input: 'Get-Content .agents/skills/alpha/SKILL.md',
    outputText: 'whole current file',
    missingOutput: false,
    outputTimestampUnknown: true,
    expectedText: 'whole current file',
  }), 'timestamp_indeterminate');
});

run('partial intent, batching, missing outputs, and historical drift stay separate', () => {
  assert.equal(classifySkillAttempt({
    input: 'Get-Content .claude/skills/alpha/SKILL.md | Select-Object -First 20',
    outputText: 'first section', missingOutput: false, expectedText: 'whole current file',
  }), 'partial_intent');
  assert.equal(classifySkillAttempt({
    input: 'Get-Content .claude/skills/alpha/SKILL.md; git diff',
    outputText: 'old revision', missingOutput: false, expectedText: 'whole current file',
  }), 'ambiguous_batched');
  assert.equal(classifySkillAttempt({
    input: 'Get-Content .claude/skills/alpha/SKILL.md',
    outputText: null, missingOutput: true, expectedText: 'whole current file',
  }), 'missing_output');
  assert.equal(classifySkillAttempt({
    input: 'Get-Content .claude/skills/alpha/SKILL.md',
    outputText: 'complete old revision', missingOutput: false, expectedText: 'whole current file',
  }), 'unproven');
});

run('aggregate report counts evidence but contains no raw transcript material', () => {
  const fixture = makeRepo();
  const inventory = inventorySkills(fixture.root, fixture.tracking);
  const secret = 'PRIVATE_TRANSCRIPT_SENTINEL';
  const exchanges = [
    exchange({ outputText: `wrapper\n${fixture.claudeText}` }),
    exchange({ sessionId: 's2', input: 'Get-Content .agents/skills/alpha/SKILL.md; git diff', outputText: `Warning: truncated output (original token count: 99999)\n${secret}` }),
    exchange({ sessionId: 's3', input: 'Get-Content .claude/skills/not-checked-in/SKILL.md', outputText: secret }),
  ];
  const report = buildSkillDeliveryReport({
    inventory,
    exchanges,
    fragmentsDiscovered: 9,
    fragmentsContributing: 7,
    sessionsScanned: 3,
    skippedFiles: 1,
    unreadableFragments: 1,
    malformedLines: 3,
    untimestampedExchanges: 2,
    duplicateExchangeCopies: 4,
    conflictingExchangeCopies: 1,
    sourceRootsAvailable: 1,
    sourceRootsMissing: 1,
    sourceRootErrorCount: 0,
    since: '2026-08-01T00:00:00.000Z', repoName: 'fixture', projectPattern: 'fixture',
  });
  assert.equal(report.schemaVersion, 2);
  assert.equal(report.scope.projectPattern, 'fixture');
  assert.equal(report.scope.transcriptWindow.basis, 'exchange-start-event-time');
  assert.equal(report.delivery.targetedExchanges, 2);
  assert.equal(report.delivery.attempts, 2);
  assert.equal(report.delivery.sessionsWithReadAttempts, 2);
  assert.equal(report.delivery.classifications.proven_full_current, 1);
  assert.equal(report.delivery.classifications.tool_output_truncated, 1);
  assert.equal(report.delivery.sessionsByClassification.tool_output_truncated, 1);
  assert.equal(report.delivery.bySkill[0].sessionsByClassification.proven_full_current, 1);
  assert.equal(report.delivery.rolloutFragmentsDiscovered, 9);
  assert.equal(report.delivery.rolloutFragmentsContributing, 7);
  assert.equal(report.delivery.unreadableFragments, 1);
  assert.equal(report.delivery.malformedLines, 3);
  assert.equal(report.delivery.untimestampedExchanges, 2);
  assert.equal(report.delivery.duplicateExchangeCopies, 4);
  assert.equal(report.delivery.conflictingExchangeCopies, 1);
  assert.equal(report.delivery.sourceRootsAvailable, 1);
  assert.equal(report.delivery.sourceRootsMissing, 1);
  assert.equal(report.inventory.presentSkillCount, 2);
  assert.equal(report.inventory.trackedSkillCount, 2);
  assert.equal(report.inventory.untrackedSkillCount, 0);
  assert.equal(report.inventory.surfaces.length, 2);
  assert.equal(report.inventory.surfaces.every((surface) => surface.checkedInSkillCount === 1), true);
  assert.equal(report.inventory.surfaces.every((surface) => surface.invalidMetadataSkillCount === 0), true);
  assert.equal(report.inventory.surfaces.every((surface) => surface.presentExplicitOnlyCount === 1), true);
  assert.equal(report.inventory.surfaces.every((surface) => surface.presentCatalogFieldCharsLowerBound > surface.presentDescriptionChars), true);
  assert.doesNotMatch(JSON.stringify(report), new RegExp(secret));
  assert.doesNotMatch(JSON.stringify(report), /Get-Content/);
  assert.equal(Object.hasOwn(report.inventory.skills[0], 'text'), false);
});

run('one exchange naming both harness authorities yields two distinct attempts', () => {
  const fixture = makeRepo();
  const report = buildSkillDeliveryReport({
    inventory: inventorySkills(fixture.root, fixture.tracking),
    exchanges: [exchange({
      input: 'Get-Content .claude/skills/alpha/SKILL.md; Get-Content .agents/skills/alpha/SKILL.md',
      outputText: 'neither current file',
    })],
  });
  assert.equal(report.delivery.targetedExchanges, 1);
  assert.equal(report.delivery.attempts, 2);
  assert.equal(report.delivery.classifications.ambiguous_batched, 2);
  assert.deepEqual(
    report.delivery.bySkill.filter((row) => row.attempts > 0).map((row) => row.harness).sort(),
    ['claude-code', 'codex-cli'],
  );
});

run('content from the other harness cannot prove the named skill path', () => {
  const fixture = makeRepo();
  const report = buildSkillDeliveryReport({
    inventory: inventorySkills(fixture.root, fixture.tracking),
    exchanges: [exchange({
      input: 'Get-Content .claude/skills/alpha/SKILL.md',
      outputText: fixture.codexText,
    })],
  });
  assert.equal(report.delivery.classifications.proven_full_current, 0);
  assert.equal(report.delivery.classifications.unproven, 1);
});

run('parseArgs defaults to a reproducible trailing-30-day millisecond window', () => {
  const now = Date.parse('2026-09-04T12:00:00.000Z');
  const opts = parseArgs(['--repo-root', 'repo', '--codex-home', 'codex', '--project-pattern', 'fixture', '--json'], now);
  assert.equal(opts.sinceMs, now - 30 * 24 * 60 * 60 * 1000);
  assert.equal(opts.projectPattern, 'fixture');
  assert.equal(opts.json, true);
});

run('parseArgs rejects missing values and an inverted window', () => {
  assert.throws(() => parseArgs(['--repo-root']), /requires a value/);
  assert.throws(
    () => parseArgs(['--since', '2026-09-04', '--until', '2026-09-03']),
    /must not be earlier/,
  );
});

run('CLI help exposes event-time and native-inventory semantics', () => {
  const result = spawnSync(process.execPath, [CLI, '--help'], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /exchange-start lower bound/);
  assert.match(result.stdout, /native skill trees/);
  assert.doesNotMatch(result.stdout, /projection|mtime/i);
});

run('CLI fails closed when neither Codex rollout root is readable', () => {
  const fixture = makeRepo();
  const missing = path.join(fixture.root, 'missing-codex-home');
  const result = spawnSync(process.execPath, [
    CLI,
    '--repo-root', fixture.root,
    '--codex-home', missing,
    '--project-pattern', 'fixture',
    '--json',
  ], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /no readable Codex rollout roots/);
  assert.equal(result.stdout, '');
});

if (failures.length) {
  console.error(`skill-delivery.test: ${failures.length} FAILED, ${passed} passed`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}
console.log(`skill-delivery.test: ${passed} passed`);
