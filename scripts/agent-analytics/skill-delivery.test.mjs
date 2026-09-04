/**
 * Unit tests for skill-delivery.mjs (tempdoc 928). No real transcripts or
 * prompts are read; fixtures are created under the OS temp directory.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  buildSkillDeliveryReport,
  classifySkillAttempt,
  findSkillTargets,
  inventorySkills,
  isSkillReadExchange,
  parseArgs,
  parseSkillFrontmatter,
} from './skill-delivery.mjs';

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
  const sourceDir = path.join(root, '.claude', 'skills', 'alpha');
  const projectedDir = path.join(root, '.agents', 'skills', 'alpha');
  fs.mkdirSync(path.join(projectedDir, 'agents'), { recursive: true });
  fs.mkdirSync(sourceDir, { recursive: true });
  const sourceText = [
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
  const projectionText = [
    '---',
    'name: alpha',
    'description: Alpha delivery fixture.',
    '---',
    'generated projection',
    'PROJECTED_CURRENT_SENTINEL',
    '',
  ].join('\n');
  fs.writeFileSync(path.join(sourceDir, 'SKILL.md'), sourceText, 'utf8');
  fs.writeFileSync(path.join(projectedDir, 'SKILL.md'), projectionText, 'utf8');
  fs.writeFileSync(path.join(projectedDir, 'agents', 'openai.yaml'), 'policy:\n  allow_implicit_invocation: false\n', 'utf8');
  return { root, sourceText, projectionText };
}

function exchange(overrides = {}) {
  return {
    sessionId: overrides.sessionId ?? 's1',
    input: overrides.input ?? 'Get-Content .claude/skills/alpha/SKILL.md',
    outputText: overrides.outputText ?? '',
    missingOutput: overrides.missingOutput ?? false,
  };
}

run('parseSkillFrontmatter separates scalar metadata from the body', () => {
  const parsed = parseSkillFrontmatter('---\r\ndescription: "Example"\r\nuser-invocable: true\r\n---\r\nBody\r\n');
  assert.equal(parsed.frontmatter.description, 'Example');
  assert.equal(parsed.frontmatter['user-invocable'], true);
  assert.equal(parsed.body, 'Body\n');
});

run('inventory reports source/projection sizes and explicit-only policy', () => {
  const fixture = makeRepo();
  const inventory = inventorySkills(fixture.root);
  assert.equal(inventory.length, 1);
  assert.equal(inventory[0].name, 'alpha');
  assert.equal(inventory[0].description, 'Alpha delivery fixture.');
  assert.equal(inventory[0].explicitOnly, true);
  assert.equal(inventory[0].sourceText, fixture.sourceText);
  assert.equal(inventory[0].projectionText, fixture.projectionText);
  assert.ok(inventory[0].bodyChars < inventory[0].totalChars);
});

run('findSkillTargets handles source, projection, and escaped Windows separators', () => {
  const targets = findSkillTargets('Get-Content F:\\\\repo\\\\.agents\\\\skills\\\\alpha\\\\SKILL.md; cat .claude/skills/beta/SKILL.md');
  assert.deepEqual(targets, [
    { kind: 'agents', name: 'alpha' },
    { kind: 'claude', name: 'beta' },
  ]);
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
  const inventory = inventorySkills(fixture.root);
  const secret = 'PRIVATE_TRANSCRIPT_SENTINEL';
  const exchanges = [
    exchange({ outputText: `wrapper\n${fixture.sourceText}` }),
    exchange({ sessionId: 's2', input: 'Get-Content .agents/skills/alpha/SKILL.md; git diff', outputText: `Warning: truncated output (original token count: 99999)\n${secret}` }),
    exchange({ sessionId: 's3', input: 'Get-Content .claude/skills/not-checked-in/SKILL.md', outputText: secret }),
  ];
  const report = buildSkillDeliveryReport({
    inventory, exchanges, filesScanned: 7, sessionsScanned: 3, skippedFiles: 1,
    since: '2026-08-01T00:00:00.000Z', repoName: 'fixture',
  });
  assert.equal(report.delivery.targetedExchanges, 2);
  assert.equal(report.delivery.attempts, 2);
  assert.equal(report.delivery.sessionsWithReadAttempts, 2);
  assert.equal(report.delivery.classifications.proven_full_current, 1);
  assert.equal(report.delivery.classifications.tool_output_truncated, 1);
  assert.equal(report.delivery.sessionsByClassification.tool_output_truncated, 1);
  assert.equal(report.delivery.bySkill[0].sessionsByClassification.proven_full_current, 1);
  assert.equal(report.inventory.explicitOnlyCount, 1);
  assert.doesNotMatch(JSON.stringify(report), new RegExp(secret));
  assert.doesNotMatch(JSON.stringify(report), /Get-Content/);
  assert.equal(Object.hasOwn(report.inventory.skills[0], 'sourceText'), false);
});

run('one exchange naming both source and projection yields two attempts', () => {
  const fixture = makeRepo();
  const report = buildSkillDeliveryReport({
    inventory: inventorySkills(fixture.root),
    exchanges: [exchange({
      input: 'Get-Content .claude/skills/alpha/SKILL.md; Get-Content .agents/skills/alpha/SKILL.md',
      outputText: 'neither current file',
    })],
  });
  assert.equal(report.delivery.targetedExchanges, 1);
  assert.equal(report.delivery.attempts, 2);
  assert.equal(report.delivery.classifications.ambiguous_batched, 2);
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

if (failures.length) {
  console.error(`skill-delivery.test: ${failures.length} FAILED, ${passed} passed`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}
console.log(`skill-delivery.test: ${passed} passed`);
